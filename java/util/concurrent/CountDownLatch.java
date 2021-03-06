/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * A synchronization aid that allows one or more threads to wait until
 * a set of operations being performed in other threads completes.
 *
 * <p>A {@code CountDownLatch} is initialized with a given <em>count</em>.
 * The {@link #await await} methods block until the current count reaches
 * zero due to invocations of the {@link #countDown} method, after which
 * all waiting threads are released and any subsequent invocations of
 * {@link #await await} return immediately.  This is a one-shot phenomenon
 * -- the count cannot be reset.  If you need a version that resets the
 * count, consider using a {@link CyclicBarrier}.
 *
 * <p>A {@code CountDownLatch} is a versatile synchronization tool
 * and can be used for a number of purposes.  A
 * {@code CountDownLatch} initialized with a count of one serves as a
 * simple on/off latch, or gate: all threads invoking {@link #await await}
 * wait at the gate until it is opened by a thread invoking {@link
 * #countDown}.  A {@code CountDownLatch} initialized to <em>N</em>
 * can be used to make one thread wait until <em>N</em> threads have
 * completed some action, or some action has been completed N times.
 *
 * <p>A useful property of a {@code CountDownLatch} is that it
 * doesn't require that threads calling {@code countDown} wait for
 * the count to reach zero before proceeding, it simply prevents any
 * thread from proceeding past an {@link #await await} until all
 * threads could pass.
 *
 * <p><b>Sample usage:</b> Here is a pair of classes in which a group
 * of worker threads use two countdown latches:
 * <ul>
 * <li>The first is a start signal that prevents any worker from proceeding
 * until the driver is ready for them to proceed;
 * <li>The second is a completion signal that allows the driver to wait
 * until all workers have completed.
 * </ul>
 *
 *  <pre> {@code
 * class Driver { // ...
 *   void main() throws InterruptedException {
 *     CountDownLatch startSignal = new CountDownLatch(1);
 *     CountDownLatch doneSignal = new CountDownLatch(N);
 *
 *     for (int i = 0; i < N; ++i) // create and start threads
 *       new Thread(new Worker(startSignal, doneSignal)).start();
 *
 *     doSomethingElse();            // don't let run yet
 *     startSignal.countDown();      // let all threads proceed
 *     doSomethingElse();
 *     doneSignal.await();           // wait for all to finish
 *   }
 * }
 *
 * class Worker implements Runnable {
 *   private final CountDownLatch startSignal;
 *   private final CountDownLatch doneSignal;
 *   Worker(CountDownLatch startSignal, CountDownLatch doneSignal) {
 *     this.startSignal = startSignal;
 *     this.doneSignal = doneSignal;
 *   }
 *   public void run() {
 *     try {
 *       startSignal.await();
 *       doWork();
 *       doneSignal.countDown();
 *     } catch (InterruptedException ex) {} // return;
 *   }
 *
 *   void doWork() { ... }
 * }}</pre>
 *
 * <p>Another typical usage would be to divide a problem into N parts,
 * describe each part with a Runnable that executes that portion and
 * counts down on the latch, and queue all the Runnables to an
 * Executor.  When all sub-parts are complete, the coordinating thread
 * will be able to pass through await. (When threads must repeatedly
 * count down in this way, instead use a {@link CyclicBarrier}.)
 *
 *  <pre> {@code
 * class Driver2 { // ...
 *   void main() throws InterruptedException {
 *     CountDownLatch doneSignal = new CountDownLatch(N);
 *     Executor e = ...
 *
 *     for (int i = 0; i < N; ++i) // create and start threads
 *       e.execute(new WorkerRunnable(doneSignal, i));
 *
 *     doneSignal.await();           // wait for all to finish
 *   }
 * }
 *
 * class WorkerRunnable implements Runnable {
 *   private final CountDownLatch doneSignal;
 *   private final int i;
 *   WorkerRunnable(CountDownLatch doneSignal, int i) {
 *     this.doneSignal = doneSignal;
 *     this.i = i;
 *   }
 *   public void run() {
 *     try {
 *       doWork(i);
 *       doneSignal.countDown();
 *     } catch (InterruptedException ex) {} // return;
 *   }
 *
 *   void doWork() { ... }
 * }}</pre>
 *
 * <p>Memory consistency effects: Until the count reaches
 * zero, actions in a thread prior to calling
 * {@code countDown()}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions following a successful return from a corresponding
 * {@code await()} in another thread.
 *
 * @since 1.5
 * @author Doug Lea
 */

/**
 * CountDownLatch是一种锁，称为闭锁。可以让一个或多个线程等待另外一个或多个线程执行完毕后再执行。
 * CountDownLatch也是基于AQS构建，使用共享模式。
 * CountDownLatch中提供一个count值来表示要等待的(其他任务)完成次数，常规用法有两种：
 * Count(1)和Count(N)。举个栗子，百米赛跑，N个选手，每个选手可以看成是一个线程。
 * 起跑前，选手准备(线程启动，然后在Count(1)上阻塞)，当发令枪响后(相当于Count(1)闭锁释放)，
 * 选手一起起跑(相当于线程通过Count(1)继续执行)，当所有选手都通过终点(相当于Count(N)闭锁释放)，然后再统
 *
 *   1.建立一个count为n的闭锁后，闭锁的内部计数为n，这时如果有线程调用闭锁的await方法，会阻塞。
 *   2.每一次调用闭锁的countDown方法，内部计数就会减1，当闭锁的countDown方法被调用n次后，内部
 *   计数减为0，这时在闭锁await方法上等待的线程就被唤醒了。
 */
public class CountDownLatch {
    /**
     * Synchronization control For CountDownLatch.
     * Uses AQS state to represent count.
     *
     * CountDownLatch内部同步器，利用AQS的state来表示count。
     *
     * // 老套路了，内部封装一个 Sync 类继承自 AQS
     *
     * 先分析套路：AQS 里面的 state 是一个整数值，这边用一个 int count
     * 参数其实初始化就是设置了这个值，所有调用了 await 方法的等待线程会挂起，
     * 然后有其他一些线程会做 state = state - 1 操作，当 state 减到 0 的同时，
     * 那个线程会负责唤醒调用了 await 方法的所有线程。都是套路啊，只是 Doug Lea
     * 的套路很深，代码很巧妙，不然我们也没有要分析源码的必要。
     *
     * 一个是 countDown() 方法，另一个是 await() 方法。countDown() 方法每次调
     * 用都会将 state 减 1，直到 state 的值为 0；而 await 是一个阻塞方法，当
     * state 减为 0 的时候，await 方法才会返回。await 可以被多个线程调用，读者
     * 这个时候脑子里要有个图：所有调用了 await 方法的线程阻塞在 AQS 的阻塞队列中，
     * 等待条件满足（state == 0），将线程从队列中一个个唤醒过来。
     */
    private static final class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 4982264981922014374L;

        Sync(int count) {
            setState(count);
        }

        int getCount() {
            return getState();
        }

        //如果当前count为0，那么方法返回1，
        //按照之前对AQS的分析，请求成功，并唤醒同步队列里下一个共享模式的线程(这里都是共享模式的)。
        //如果当前count不为0，那么方法返回-1，请求失败，当前线程最终会被阻塞(之前会不止一次调用tryAcquireShared)。

        // 只有当 state == 0 的时候，这个方法才会返回 1，否者返回-1
        protected int tryAcquireShared(int acquires) {
            return (getState() == 0) ? 1 : -1;
        }

        // 如果count为0，返回false，相当于释放失败，因为此时闭锁处于开放状态，没有必要在打开。
        // 如果count不为0，递减count。
        // 最后如果count为0，说明关闭的闭锁打开了，那么返回true，后面会唤醒等待队列中的线程。
        // 如果count不为0，说明闭锁还是处于关闭状态，返回false。

        // 这个方法很简单，用自旋的方法实现 state 减 1
        protected boolean tryReleaseShared(int releases) {
            // Decrement count; signal when transition to zero
            for (;;) {
                int c = getState();
                if (c == 0)
                    return false;
                int nextc = c-1;
                if (compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }
    }

    private final Sync sync;

    /**
     * Constructs a {@code CountDownLatch} initialized with the given count.
     *
     * @param count the number of times {@link #countDown} must be invoked
     *        before threads can pass through {@link #await}
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public CountDownLatch(int count) {
        if (count < 0) throw new IllegalArgumentException("count < 0");
        this.sync = new Sync(count);
    }

    /**
     * Causes the current thread to wait until the latch has counted down to
     * zero, unless the thread is {@linkplain Thread#interrupt interrupted}.
     *
     * <p>If the current count is zero then this method returns immediately.
     *
     * <p>If the current count is greater than zero then the current
     * thread becomes disabled for thread scheduling purposes and lies
     * dormant until one of two things happen:
     * <ul>
     * <li>The count reaches zero due to invocations of the
     * {@link #countDown} method; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     */
    /**
     * 如果当前count为0，那么方法立即返回。
     *
     * 如果当前count不为0，那么当前线程会一直等待，直到count被(其他线程)减到0或者当前线程被中断。
     */
    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * Causes the current thread to wait until the latch has counted down to
     * zero, unless the thread is {@linkplain Thread#interrupt interrupted},
     * or the specified waiting time elapses.
     *
     * <p>If the current count is zero then this method returns immediately
     * with the value {@code true}.
     *
     * <p>If the current count is greater than zero then the current
     * thread becomes disabled for thread scheduling purposes and lies
     * dormant until one of three things happen:
     * <ul>
     * <li>The count reaches zero due to invocations of the
     * {@link #countDown} method; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>The specified waiting time elapses.
     * </ul>
     *
     * <p>If the count reaches zero then the method returns with the
     * value {@code true}.
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then the value {@code false}
     * is returned.  If the time is less than or equal to zero, the method
     * will not wait at all.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the {@code timeout} argument
     * @return {@code true} if the count reached zero and {@code false}
     *         if the waiting time elapsed before the count reached zero
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     */
    /**
     * 如果当前count为0，那么方法立即返回true。
     *
     * 如果当前count不为0，那么当前线程会一直等待，直到count被(其他线程)减到0或者当前线程被中断或者超时。
     * 成功返回true，超时返回false，被中断抛异常。
     */
    public boolean await(long timeout, TimeUnit unit)
        throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /**
     * Decrements the count of the latch, releasing all waiting threads if
     * the count reaches zero.
     *
     * <p>If the current count is greater than zero then it is decremented.
     * If the new count is zero then all waiting threads are re-enabled for
     * thread scheduling purposes.
     *
     * <p>If the current count equals zero then nothing happens.
     */
    /**
     * 如果当前count大于0，递减count。如果递减后，count等于0，那么AQS中所有等待线程都被唤醒。
     *
     * 如果当前count等于0，什么事都不会发生。
     */
    public void countDown() {
        sync.releaseShared(1);
    }

    /**
     * Returns the current count.
     *
     * <p>This method is typically used for debugging and testing purposes.
     *
     * @return the current count
     */
    /**
     * 获取当前count值。
     */
    public long getCount() {
        return sync.getCount();
    }

    /**
     * Returns a string identifying this latch, as well as its state.
     * The state, in brackets, includes the String {@code "Count ="}
     * followed by the current count.
     *
     * @return a string identifying this latch, as well as its state
     */
    public String toString() {
        return super.toString() + "[Count = " + sync.getCount() + "]";
    }
}
