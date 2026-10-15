# Bounded background tasks

`BoundedTaskRepository` is an opt-in implementation of `TaskRepository`. Existing
constructors and builders continue to use `DefaultTaskRepository` by default.

## Explicit setup

```java
try (BoundedTaskRepository repository =
        new BoundedTaskRepository(4, 16, 100, Duration.ofMinutes(10))) {
    List<Tool> tools = TaskToolsBuilder.builder()
        .taskRepository(repository)
        .subAgent("worker", workerAgent)
        .build();
    // Use these tools while the repository is open.
}
```

The arguments specify execution slots, waiting capacity, retained result count and
result retention time. Execution slots must be positive; waiting capacity and result
count may be zero. Retention must be positive and representable in nanoseconds.

- A full repository rejects a submission with `RejectedExecutionException` before
  invoking its supplier. Suppliers are not run in the submitting thread as a fallback.
- Duplicate task IDs are rejected while queued, executing, or retaining a result.
  An ID can be reused after its completed result is removed, evicted or expired.
- Closed repositories reject first. While open, duplicate IDs are checked before
  capacity. Rejection messages are diagnostic text; callers should catch the exception
  type instead of parsing messages as reason codes.
- Successful, failed and cancelled results use the same retention policy. A task
  whose supplier is still running remains active even if its handle was cancelled.
- Retention starts when execution exits. The oldest completed results are evicted
  when the count limit is exceeded; reads and submissions also discard expired results.
  One daemon cleanup task reclaims idle expired results, with a period between 1 ms
  and 1 second derived from retention. Scheduling delays can delay physical cleanup.
- Zero retained results means completed tasks are no longer discoverable in the
  repository. Callers can still read results through handles they already hold.
- Automatic cleanup never removes active tasks. Explicit `removeTask` and `clear`
  hide records from queries, but do not cancel active execution or release its ID
  and capacity prematurely. Hidden active tasks do not reappear when they finish.
- Cancelling a queued task frees its queue position without invoking its supplier.
  Cancelling a running or already submitted task does not free its execution slot
  until its wrapper exits. Cancellation does not guarantee interruption of a supplier.

The count bound limits retained records, not the byte size of each result. This is a
local in-process repository, with no durable recovery or permanent deduplication.

Execution slots include wrappers which have been submitted but have not started.
With a single-thread executor, four execution slots and one waiting position, four
accepted tasks occupy all slots: one supplier runs and three wrappers wait in the
executor. A fifth task waits inside the repository; a sixth is rejected without adding
another executor command. This bound applies to this repository's commands, independently
of other work submitted directly to a shared executor.

| State when explicitly removed | Discoverable afterwards | ID reusable immediately |
| --- | --- | --- |
| Waiting inside the repository | No | No, until safely cancelled or execution exits |
| Reserved or submitted but not started | No | No, until safely retired or execution exits |
| Supplier running, including a cancelled handle | No | No, until the supplier exits |
| Execution exited and result retained | No | Yes, subject to normal admission checks |

`clear()` follows the same rules for every record. Removing a record only hides it;
it does not cancel active work or permit duplicate execution while that work remains.

## Executor ownership and Spring shutdown

The four-argument constructor owns its execution pool. The overload
`new BoundedTaskRepository(callerExecutor, 4, 16, 100, retention)` borrows an
`ExecutorService` and never shuts it down. Repository limits apply even if the borrowed
executor has more threads. An executor must eventually run accepted commands or throw
on rejection; silent discard policies are unsupported. Avoid using an executor whose
threads synchronously wait for tasks submitted back to that same executor.

Closing stops admission, cancels work that has not started, and stops repository-owned
cleanup. It waits up to five seconds for an owned execution pool, then requests
interruption. Java cannot forcibly stop a supplier which ignores interruption. Running
suppliers on a borrowed executor may finish normally after repository close; unrelated
work on that executor is unaffected. A queued handle whose executor later rejects
dispatch is completed exceptionally rather than left pending.

If `executor.execute` throws a `RuntimeException` or `Error`, only that task is retired,
its capacity is released and its future is completed exceptionally. Dispatch continues
for other accepted tasks without retrying the failed task. Before a handle is returned,
the original submitting call receives its own task's failure after its `drain` call
returns; an already returned handle receives the failure through its future. A different
thread performing dispatch does not rethrow another caller's submission failure.
Recovery from fatal JVM errors is not guaranteed.

Close the repository before shutting down a borrowed executor so its accepted,
not-yet-started handles can be completed rather than abandoned by that executor.

Use an explicit destroy method when registering the repository as a Spring singleton:

```java
@Bean(destroyMethod = "close")
BoundedTaskRepository taskRepository() {
    return new BoundedTaskRepository(4, 16, 100, Duration.ofMinutes(10));
}

@Bean
List<Tool> taskTools(BoundedTaskRepository repository, ReactAgent workerAgent) {
    return TaskToolsBuilder.builder()
        .taskRepository(repository)
        .subAgent("worker", workerAgent)
        .build();
}
```

`TaskToolsBuilder` does not close a supplied repository. Register the repository itself
as a bean or arrange for the application to close it. No new starter, automatic
configuration, property prefix, checkpoint format or provider SDK is required.
