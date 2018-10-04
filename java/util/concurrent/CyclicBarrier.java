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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A synchronization aid that allows a set of threads to all wait for
 * each other to reach a common barrier point.  CyclicBarriers are
 * useful in programs involving a fixed sized party of threads that
 * must occasionally wait for each other. The barrier is called
 * <em>cyclic</em> because it can be re-used after the waiting threads
 * are released.
 *
 * <p>A {@code CyclicBarrier} supports an optional {@link Runnable} command
 * that is run once per barrier point, after the last thread in the party
 * arrives, but before any threads are released.
 * This <em>barrier action</em> is useful
 * for updating shared-state before any of the parties continue.
 *
 * <p><b>Sample usage:</b> Here is an example of using a barrier in a
 * parallel decomposition design:
 *
 *  <pre> {@code
 * class Solver {
 *   final int N;
 *   final float[][] data;
 *   final CyclicBarrier barrier;
 *
 *   class Worker implements Runnable {
 *     int myRow;
 *     Worker(int row) { myRow = row; }
 *     public void run() {
 *       while (!done()) {
 *         processRow(myRow);
 *
 *         try {
 *           barrier.await();
 *         } catch (InterruptedException ex) {
 *           return;
 *         } catch (BrokenBarrierException ex) {
 *           return;
 *         }
 *       }
 *     }
 *   }
 *
 *   public Solver(float[][] matrix) {
 *     data = matrix;
 *     N = matrix.length;
 *     Runnable barrierAction =
 *       new Runnable() { public void run() { mergeRows(...); }};
 *     barrier = new CyclicBarrier(N, barrierAction);
 *
 *     List<Thread> threads = new ArrayList<Thread>(N);
 *     for (int i = 0; i < N; i++) {
 *       Thread thread = new Thread(new Worker(i));
 *       threads.add(thread);
 *       thread.start();
 *     }
 *
 *     // wait until done
 *     for (Thread thread : threads)
 *       thread.join();
 *   }
 * }}</pre>
 *
 * Here, each worker thread processes a row of the matrix then waits at the
 * barrier until all rows have been processed. When all rows are processed
 * the supplied {@link Runnable} barrier action is executed and merges the
 * rows. If the merger
 * determines that a solution has been found then {@code done()} will return
 * {@code true} and each worker will terminate.
 *
 * <p>If the barrier action does not rely on the parties being suspended when
 * it is executed, then any of the threads in the party could execute that
 * action when it is released. To facilitate this, each invocation of
 * {@link #await} returns the arrival index of that thread at the barrier.
 * You can then choose which thread should execute the barrier action, for
 * example:
 *  <pre> {@code
 * if (barrier.await() == 0) {
 *   // log the completion of this iteration
 * }}</pre>
 *
 * <p>The {@code CyclicBarrier} uses an all-or-none breakage model
 * for failed synchronization attempts: If a thread leaves a barrier
 * point prematurely because of interruption, failure, or timeout, all
 * other threads waiting at that barrier point will also leave
 * abnormally via {@link BrokenBarrierException} (or
 * {@link InterruptedException} if they too were interrupted at about
 * the same time).
 *
 * <p>Memory consistency effects: Actions in a thread prior to calling
 * {@code await()}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions that are part of the barrier action, which in turn
 * <i>happen-before</i> actions following a successful return from the
 * corresponding {@code await()} in other threads.
 *
 * @since 1.5
 * @see CountDownLatch
 *
 * @author Doug Lea
 */


/**
 * CyclicBarrier是一种可重复使用的栅栏机制，可以让一组线程在某个点上相互等待，这个
 * 点就可以类比为栅栏。并且这个栅栏是可重复使用的，这点可以和前面分析过的CountDownLatch
 * 做对比，CountDownLatch只能用一次。CyclicBarrier还支持在所有线程到达栅栏之后，在所有
 * 线程从等待状态转到可运行状态之前，执行一个命令(或者说是动作)。当然，在某些情况下，栅栏
 * 可以被打破。比如某个线程无法在规定的时
 * -----------------------------------------
 * CyclicBarrier内部使用ReentrantLock来实现，并包含一个trip条件，来作为栅栏模拟栅栏的行为
 * (所有使用方都在这个条件上等待)。
 * -----------------------------------------
 * 1.当建立一个使用方数量为n的栅栏时，栅栏内部有一个为n的计数。当使用方调用await方法时，如果
 * 其他n-1个使用方没有全部到达await方法(内部计数减1后，不等于0)，那么使用方(线程)阻塞等待。
 * 2.当第n个使用方调用await时，栅栏开放(内部计数减1后等于0)，会唤醒所有在await方法上等待着的使
 * 用方(线程)，大家一起通过栅栏，然后重置栅栏(内部计数又变成n)，栅栏变成新建后的状态，可以再次使用。
 *
 * CyclicBarrier 的源码实现和 CountDownLatch 大相径庭，CountDownLatch 基于 AQS 的共享模式的
 * 使用，而 CyclicBarrier 基于 Condition 来实现。
 *
 */
public class CyclicBarrier {
    /**
     * Each use of the barrier is represented as a generation instance.
     * The generation changes whenever the barrier is tripped, or
     * is reset. There can be many generations associated with threads
     * using the barrier - due to the non-deterministic way the lock
     * may be allocated to waiting threads - but only one of these
     * can be active at a time (the one to which {@code count} applies)
     * and all the rest are either broken or tripped.
     * There need not be an active generation if there has been a break
     * but no subsequent reset.
     */

    /**
     * 每次对栅栏的使用可以表示为一个generation。栅栏每次开放或者重置，
     * generation都会发生改变。使用栅栏的线程可以关联多个generations，
     * 由于等待线程可能会以多种方式请求锁，但是在特定的时间只有一个是
     * 可用的，其他的要么被打破，要么开放。如果一个栅栏已经被打破。且没有
     * 后续的重置动作，那么可以不存在可用的generation。
     */

    // 我们说了，CyclicBarrier 是可以重复使用的，我们把每次从开始使用到穿过栅栏当做"一代"
    private static class Generation {
        boolean broken = false;
    }

    /** The lock for guarding barrier entry */
    /** 用于保护栅栏的锁 */
    private final ReentrantLock lock = new ReentrantLock();
    /** Condition to wait on until tripped */
    /** 栅栏开放的条件 */
    // CyclicBarrier 是基于 Condition 的
    // Condition 是“条件”的意思，CyclicBarrier 的等待线程通过 barrier 的“条件”是大家都到了栅栏上
    private final Condition trip = lock.newCondition();
    /** The number of parties */
    /** 表示当前使用栅栏的使用方(线程)数量 */
    // 参与的线程数
    private final int parties;
    /* The command to run when tripped */
    // 如果设置了这个，代表越过栅栏之前，要执行相应的操作
    private final Runnable barrierCommand;
    /** The current generation */
    // 当前所处的“代”
    private Generation generation = new Generation();

    /**
     * Number of parties still waiting. Counts down from parties to 0
     * on each generation.  It is reset to parties on each new
     * generation or when broken.
     */

    /**
     * 处于等待状态的使用方(线程)的数量，在每一个generation上从
     * parties递减为0。当新建generation(栅栏开放)或者栅栏被打破
     * 时，重置为parties。
     */
    // 还没有到栅栏的线程数，这个值初始为 parties，然后递减
    // 还没有到栅栏的线程数 = parties - 已经到栅栏的数量
    private int count;

    /**
     * Updates state on barrier trip and wakes up everyone.
     * Called only while holding lock.
     */

    /**
     * 更新栅栏状态，唤醒所有在栅栏处等待的线程。
     * 这个方法只有在持有锁的情况下被调用。
     *
     * 开启新一代栅栏
     */
    // 开启新的一代，当最后一个线程到达栅栏上的时候，调用这个方法来唤醒其他线程，同时初始化“下一代”
    private void nextGeneration() {
        // signal completion of last generation
        // 首先，需要唤醒所有的在栅栏上等待的线程
        trip.signalAll();
        // set up next generation
        // 更新 count 的值
        count = parties;
        // 重新生成“新一代”
        generation = new Generation();
    }

    /**
     * Sets current barrier generation as broken and wakes up everyone.
     * Called only while holding lock.
     */
    /**
     * 设置当前栅栏generation状态为打破状态，并唤醒栅栏处的等待线程。
     * 这个方法只有在持有锁的情况下被调用。
     *
     * 打破一个栅栏
     *
     * 什么时候栅栏会被打破，总结如下：

     * 1.中断，我们说了，如果某个等待的线程发生了中断，那么会打破栅栏，同时抛出 InterruptedException 异常；
     * 2.超时，打破栅栏，同时抛出 TimeoutException 异常；
     * 3.指定执行的操作抛出了异常，这个我们前面也说过。
     */
    private void breakBarrier() {
        // 设置状态 broken 为 true
        generation.broken = true;
        // 重置 count 为初始值 parties
        count = parties;
        // 唤醒所有已经在等待的线程
        trip.signalAll();
    }

    /**
     * Main barrier code, covering the various policies.
     */

    /**
     * 栅栏主体代码，涵盖了所有情况。
     */
    private int dowait(boolean timed, long nanos)
        throws InterruptedException, BrokenBarrierException,
               TimeoutException {
        final ReentrantLock lock = this.lock;
        // 先要获取到锁，然后在 finally 中要记得释放锁
        // 如果记得 Condition 部分的话，我们知道 condition 的 await 会释放锁，signal 的时候需要重新获取锁
        lock.lock();
        try {
            //获取当前的generation实例。
            final Generation g = generation;

            //如果当前generation状态为broken，说明栅栏被打破，抛出BrokenBarrierException异常。
            if (g.broken)
                throw new BrokenBarrierException();
            //如果当前线程被中断，打破栅栏，然后抛出中断异常。
            if (Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }
            // 计算当前到达线程的下标。
            // index 是这个 await 方法的返回值
            // 注意到这里，这个是从 count 递减后得到的值
            int index = --count;
            //下标为0表示当前线程为最后一个使用栅栏的线程。
            // 如果等于 0，说明所有的线程都到栅栏上了，准备通过
            if (index == 0) {  // tripped // 栅栏开放。
                boolean ranAction = false;
                try {
                    // 如果在初始化的时候，指定了通过栅栏前需要执行的操作，在这里会得到执行
                    final Runnable command = barrierCommand;
                    if (command != null) //如果有栅栏命令，执行栅栏命令。
                        command.run(); //看来栅栏的命令是由最后一个到达栅栏的线程执行。
                    // 如果 ranAction 为 true，说明执行 command.run() 的时候，没有发生异常退出的情况
                    ranAction = true;
                    //产生新的generation。
                    // 唤醒等待的线程，然后开启新的一代
                    nextGeneration();
                    return 0;
                } finally {
                    // 进到这里，说明执行指定操作的时候，发生了异常，那么需要打破栅栏
                    // 之前我们说了，打破栅栏意味着唤醒所有等待的线程，设置 broken 为 true，重置 count 为 parties
                    if (!ranAction)
                        breakBarrier();
                }
            }

            // loop until tripped, broken, interrupted, or timed out
            // 等待中的主循环，直到栅栏开放、栅栏被打破、线程被打断或者超时时退出。

            // 如果是最后一个线程调用 await，那么上面就返回了
            // 下面的操作是给那些不是最后一个到达栅栏的线程执行的
            for (;;) {
                try {
                    // 如果带有超时机制，调用带超时的 Condition 的 await 方法等待，直到最后一个线程调用 await
                    if (!timed)
                        trip.await();
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    //如果出于当前generation 且generation状态为未打破，那么打破栅栏。
                    // 如果到这里，说明等待的线程在 await（是 Condition 的 await）的时候被中断
                    if (g == generation && ! g.broken) {
                        // 打破栅栏
                        breakBarrier();
                        // 打破栅栏后，重新抛出这个 InterruptedException 异常给外层调用的方法
                        throw ie;
                    } else {
                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.

                        // 如果没被中断的话，我们即将完成等待。
                        // 所以这个中断被算作下一次执行的中断。

                        // 到这里，说明 g != generation, 说明新的一代已经产生，即最后一个线程 await 执行完成，
                        // 那么此时没有必要再抛出 InterruptedException 异常，记录下来这个中断信息即可
                        // 或者是栅栏已经被打破了，那么也不应该抛出 InterruptedException 异常，
                        // 而是之后抛出 BrokenBarrierException 异常

                        //下面这句代码不起任何作用，仅仅是一个标记，表明，这个线程被中断过，如果要处理，需要操作者手动去处理
                        Thread.currentThread().interrupt();
                    }
                }
                // 唤醒后，检查栅栏是否是“破的”
                if (g.broken)
                    throw new BrokenBarrierException();
                // 如果generation改变了，说明之前的栅栏已经开放，返回index
                // 这个 for 循环除了异常，就是要从这里退出了
                // 我们要清楚，最后一个线程在执行完指定任务(如果有的话)，会调用 nextGeneration 来开启一个新的代
                // 然后释放掉锁，其他线程从 Condition 的 await 方法中得到锁并返回，然后到这里的时候，其实就会满足 g != generation 的
                // 那什么时候不满足呢？barrierCommand 执行过程中抛出了异常，那么会执行打破栅栏操作，
                // 设置 broken 为true，然后唤醒这些线程。这些线程会从上面的 if (g.broken) 这个分支抛 BrokenBarrierException 异常返回
                // 当然，还有最后一种可能，那就是 await 超时，此种情况不会从上面的 if 分支异常返回，也不会从这里返回，会执行后面的代码
                if (g != generation)
                    return index;
                //如果超时，打破栅栏，并返回超时异常。
                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and which
     * will execute the given barrier action when the barrier is tripped,
     * performed by the last thread entering the barrier.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @param barrierAction the command to execute when the barrier is
     *        tripped, or {@code null} if there is no action
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and
     * does not perform a predefined action when the barrier is tripped.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    /**
     * Returns the number of parties required to trip this barrier.
     *
     * @return the number of parties required to trip this barrier
     */
    public int getParties() {
        return parties;
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier.
     *
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while waiting,
     * then all other waiting threads will throw
     * {@link BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was
     *         broken when {@code await} was called, or the barrier
     *         action (if present) failed due to an exception
     */

    /**
     * 线程调用此方法后等待，直到所有parties都调用当前barrier的这个方法。
     *
     * 如果当前线程不是最后一个到达此方法的线程，那么会阻塞，直到下面的
     * 事情发生：
     *
     * 最后的线程也到达此方法。
     * 其他线程中断了当前线程。
     * 其他线程中断了在栅栏处等待的某个线程。
     * 某个线程调用了栅栏的reset方法。
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier, or the specified waiting time elapses.
     *
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>The specified timeout elapses; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then {@link TimeoutException}
     * is thrown. If the time is less than or equal to zero, the
     * method will not wait at all.
     *
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while
     * waiting, then all other waiting threads will throw {@link
     * BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @param timeout the time to wait for the barrier
     * @param unit the time unit of the timeout parameter
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws TimeoutException if the specified timeout elapses.
     *         In this case the barrier will be broken.
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was broken
     *         when {@code await} was called, or the barrier action (if
     *         present) failed due to an exception
     */

    /**
     * 和上面的方法相比，多了超时的情况。
     */
    public int await(long timeout, TimeUnit unit)
        throws InterruptedException,
               BrokenBarrierException,
               TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    /**
     * Queries if this barrier is in a broken state.
     *
     * @return {@code true} if one or more parties broke out of this
     *         barrier due to interruption or timeout since
     *         construction or the last reset, or a barrier action
     *         failed due to an exception; {@code false} otherwise.
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets the barrier to its initial state.  If any parties are
     * currently waiting at the barrier, they will return with a
     * {@link BrokenBarrierException}. Note that resets <em>after</em>
     * a breakage has occurred for other reasons can be complicated to
     * carry out; threads need to re-synchronize in some other way,
     * and choose one to perform the reset.  It may be preferable to
     * instead create a new barrier for subsequent use.
     *
     * 我们设想一下，如果初始化时，指定了线程 parties = 4，前面有 3 个线程调用了 await 等待，
     * 在第 4 个线程调用 await 之前，我们调用 reset 方法，那么会发生什么？

     * 首先，打破栅栏，那意味着所有等待的线程（3个等待的线程）会唤醒，await 方法会通过抛出
     * BrokenBarrierException 异常返回。然后开启新的一代，重置了 count 和 generation，相当于一切归零了。
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of parties currently waiting at the barrier.
     * This method is primarily useful for debugging and assertions.
     *
     * @return the number of parties currently blocked in {@link #await}
     *
     * 得到有多少个线程到了栅栏上，处于等待状态：
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
