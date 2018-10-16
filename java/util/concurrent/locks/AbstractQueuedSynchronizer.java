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

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h3>Usage</h3>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 *
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 *  <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 *
 *
 * ---------------------
 * AbstractQueuedSynchronizer(以下简称AQS)是Java并发包提供的一个同步基础机制，
 * 是并发包中实现Lock和其他同步机制(如:Semaphore、CountDownLatch和FutureTask等)的基础。
 *
 * AQS内部包含一个FIFO的同步等待队列，简单的说，没有成功获取控制权的线程会在这个队列中等待。
 * AQS内部管理了一个原子的int域作为内部状态信息，并提供了一些方法来访问该域，基于AQS实现的
 * 同步机制可以按自己的需要来灵活使用这个int域，比如:ReentrantLock用它记录锁重入次数；
 * CountDownLatch用它表示内部的count；FutureTask用它表示任务运行状态(Running,Ran和Cancelled)；
 * Semaphore用它表示许可数量。
 *
 * AQS提供了独占和共享两种模式。在独占模式下，当一个线程获取了AQS的控制权，其他线程获取控制权的操作
 * 就会失败；但在共享模式下，其他线程的获取控制权操作就可能成功。
 *
 * 并发包中的同步机制如ReentrantLock就是典型的独占模式，Semaphore是共享模式；也有同时使用两种模式的
 * 同步机制，如ReentrantReadWriteLock。AQS内部提供了一个ConditionObject类来支持独占模式下的(锁)条
 * 件，这个条件的功能与Object的wait和notify/notifyAll的功能类似，但更加明确和易用。
 *
 * AQS一般的使用方式为定义一个实现AQS的非公有的内部帮助类作为内部代理，来实现具体同步机制的方法，如Lock的
 * lock和unlock；AQS中也提供一些检测和监控内部队列和条件对象的方法，具体同步机制可以按需使用这些方法；AQS
 * 内部只有一个状态， 即原子int域，如果基于AQS实现的类需要做序列化/反序列化，注意这一点。
 *
 * --------------------------------------------------
 * 上面已经看到AQS内部的整体数据结构，一个同步等待队列+一个(原子的)int域。
 *
 *
 * AQS继承了类java.util.concurrent.locks.AbstractOwnableSynchronizer
 * 这个类提供了独占模式下的同步器控制权的信息，比如Lock或者其他相关的同步器。
 * 从代码中也可以看到，可以设置和获取拥有独占控制权的线程信息。
 *
 * java.util.concurrent.locks包还提供了一个AbstractQueuedLongSynchronizer同步基础类，
 * 内部代码和AQS基本一致，唯一区别是AbstractQueuedLongSynchronizer中管理的是一个long型的状态，
 * 需要构建使用64bit信息的同步器可以基于这个类进行构建，用法和AQS一致
 */
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.  A
     * "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread. The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     *
     * <p>To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you just set the head field.
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * <p>Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * <p>The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * <p>We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.  Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     *
     * <p>Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * <p>CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * <p>Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */

    /**
     *   首先我们先做一个简单的概览，内部的同步等待队列是由一系列节点组成的一个链表。如果要将一个线程入队
     *   (竞争失败，进入队列等待)，只需将这个线程及相关信息组成一个节点，拼接到队列链表尾部(尾节点)即可；
     *   如果要将一个线程出队(竞争成功)，只需重新设置新的队列首部(头节点)即可。
     *
     *
     *   节点类Node内部定义了一些常量，如节点模式、等待状态；Node内部有指向其前驱和后继节点的引用(类似双
     *   向链表)；Node内部有保存当前线程的引用；Node内部的nextWaiter域在共享模式下指向一个常量SHARED，
     *   在独占模式下为null或者是一个普通的等待条件队列(只有独占模式下才存在等待条件)
     */
    static final class Node {
        /** Marker to indicate a node is waiting in shared mode */
        /**
         * 表示节点在共享模式下等待的常量
         */
        // 标识节点当前在共享模式下
        static final Node SHARED = new Node();
        /** Marker to indicate a node is waiting in exclusive mode */

        /**
         * 表示节点在独占模式下等待的常量
         */
        // 标识节点当前在独占模式下
        static final Node EXCLUSIVE = null;

        /** waitStatus value to indicate thread has cancelled */
        /**
         * 表示当前节点的线程被取消
         */
        // 代表此线程取消了争抢这个锁
        static final int CANCELLED =  1;
        /** waitStatus value to indicate successor's thread needs unparking */
        /**
         * 表示后继节点的线程需要被唤醒
         */
        // 官方的描述是，其表示当前node的后继节点对应的线程需要被唤醒
        static final int SIGNAL    = -1;
        /** waitStatus value to indicate thread is waiting on condition */

        /**
         * 表示当前节点的线程正在等待某个条件
         */
        static final int CONDITION = -2;
        /**
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         */
        /**
         * 表示接下来的一个共享模式请求(acquireShared)要无条件的传递下去（往后继节点方向）
         */
        static final int PROPAGATE = -3;

        /**
         * 等待状态域, 取以下值:
         *   SIGNAL:     当前节点的后继节点已经(或即将)被阻塞，所以如果当前节点释放(控制权)
         *               或者被取消时，必须唤醒其后继节点。为了避免竞争，请求方法必须首先
         *               声明它们需要一个信号，然后(原子的)调用请求方法，如果失败，当前线程
         *               进入阻塞状态。
         *   CANCELLED:  表示当前节点已经被取消(由于超时或中断)，节点一旦进入被取消状态，就
         *               不会再变成其他状态了。具体来说，一个被取消节点的线程永远不会再次被
         *               阻塞
         *   CONDITION:  表示当前节点正处在一个条件队列中。当前节点直到转移时才会被作为一个
         *               同步队列的节点使用。转移时状态域会被设置为0。(使用0值和其他定义值
         *               并没有关系，只是为了简化操作)
         *   PROPAGATE:  表示一个共享的释放操作(releaseShared)应该被传递到其他节点。该状态
         *               值在doReleaseShared过程中进行设置(仅在头节点)，从而保证持续传递，
         *               即使其他操作已经开始。
         *   0:          None of the above
         *
         * 这些状态值之所以用数值来表示，目的是为了方便使用，非负的值意味着节点不需要信号(被唤醒)。
         * 所以，一些代码中不需要针对特殊值去做检测，只需要检查符号(正负)即可。
         *
         * 针对普通的同步节点，这个域被初始化为0；针对条件(condition)节点，初始化为CONDITION(-2)
         * 需要通过CAS操作来修改这个域(如果可能的话，可以使用volatile写操作)。
         */
        // 取值为上面的1、-1、-2、-3，或者0(以后会讲到)
        // 这么理解，暂时只需要知道如果这个值 大于0 代表此线程取消了等待，
        // 也许就是说半天抢不到锁，不抢了，ReentrantLock是可以指定timeouot的。。。
        volatile int waitStatus;

        /**
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         */

        /**
         * ????
         * 指向当前节点的前驱节点，用于检测等待状态。这个域在入队时赋值，出队时置空。
         * 而且，在取消前驱节点的过程中，可以缩短寻找非取消状态节点的过程。由于头节点
         * 永远不会取消(一个节点只有请求成功才会变成头节点，一个被取消的节点永远不可
         * 能请求成功，而且一个线程只能取消自己所在的节点)，所以总是存在一个非取消状态节点。
         */
        volatile Node prev;

        /**
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         */

        /**
         * ????
         * 指向当前节点的后继节点，释放(控制权)时会唤醒该节点。这个域在入队时赋值，在跳过
         * 取消状态节点时进行调整，在出队时置空。入队操作在完成之前并不会对一个前驱节点的
         * next域赋值(因为有多线程么？)，所以一个节点的next域为null并不能说明这个节点在队列尾部。然而，如果
         * next域为null，我们可以从尾节点（？？？这里有点问题）通过前驱节点往前扫描来做双重检测。取消状态节点的
         * next域指向自身(??????)，这样可以简化isOnSyncQueue的实现。
         */
        volatile Node next;

        /**
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         */

        /**
         * 使当前节点入队的线程。在构造构造的时候初始化，使用后置为null。
         */
        // 这个就是线程本尊
        volatile Thread thread;

        /**
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         */

        /**
         * 指向下一个条件等待状态节点或者为特殊值(SHARED)。由于条件队列只有在独占模式下才
         * 能访问，所以我们只需要一个普通的链表队列来保存处于等待状态的节点。它们在重新请
         * 求的时候会转移到同步队列。由于条件只存在于独占模式下，所以如果是共享模式，就将
         * 这域保存为一个特殊值(SHARED)。
         */
        Node nextWaiter;

        /**
         * Returns true if node is waiting in shared mode.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     */

    /**
     * 同步等待队列的头节点，延迟初始化。除了初始化之外，只能通过setHead方法来改变
     * 这个域。注：如果头结点存在，那么它的waitStatus可以保证一定不是CANCELLED。
     */
    // 头结点，你直接把它当做当前持有锁的线程可能是最好理解的
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     */

    /**
     * 同步等待队列的尾节点，延迟初始化。只有通过enq方法添加一个新的等待节点的时候
     * 才会改变这个域。
     */
    // 阻塞的尾节点，每个新的节点进来，都插入到最后，也就形成了一个隐视的链表
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    /**
     * 内部状态值
     */
    // 这个是最重要的，不过也是最简单的，代表当前锁的状态，0代表没有被占用，大于0代表有线程持有当前锁
    // 之所以说大于0，而不是等于1，是因为锁可以重入嘛，每次重入都加上1
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     *         value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * Inserts node into queue, initializing if necessary. See picture above.
     * @param node the node to insert
     * @return node's predecessor
     */

    // 采用自旋的方式入队
    // 之前说过，到这个方法只有两种可能：等待队列为空，或者有线程竞争入队，
    // 自旋在这边的语义是：CAS设置tail过程中，竞争一次竞争不到，我就多次竞争，总会排到的
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            // 之前说过，队列为空也会进来这里
            if (t == null) { // Must initialize
                /**
                 * 如果同步等待队列尾节点为null，说明还没有任何线程进入同步等待队列，
                 * 这时要初始化同步等待队列：创建一个(dummy)节点，然后尝试将这个
                 * 节点设置(CAS)为头节点，如果设置成功，将尾节点指向头节点
                 * 也就是说，第一次有线程进入同步等待队列时，要进行初始化，初始化
                 * 的结果就是头尾节点都指向一个哑(dummy)节点。
                 */
                // 初始化head节点
                // 原来head和tail初始化的时候都是null，反正我不细心
                // 还是一步CAS，你懂的，现在可能是很多线程同时进来呢，初始化的头结点是新的节点，和线程无关
                if (compareAndSetHead(new Node()))

                    // 给后面用：这个时候head节点的waitStatus==0, 看new Node()构造方法就知道了

                    // 这个时候有了head，但是tail还是null，设置一下，
                    // 把tail指向head，放心，马上就有线程要来了，到时候tail就要被抢了
                    // 注意：这里只是设置了tail=head，这里可没return哦，没有return，没有return
                    // 所以，设置完了以后，继续for循环，下次就到下面的else分支了
                    tail = head;//难道头结点不会被消费？？？？
            } else {
                /**
                 * 将当前(线程)节点的前驱节点指向同步等待队列的尾节点
                 */
                node.prev = t;
                //注意节点拼接到同步等待队列总是分为3个步骤：
                // 1.将其prev引用指向尾节点
                // 2.尝试将其设置为尾节点
                // 3.将其prev节点(第2步之前的尾节点)的next指向其本身。
                //所以一个节点为尾节点，可以保证prev一定不为null，但无法保证其prev的next不为null。
                //所以后续的一些方法内会看到很多对同步等待队列的反向遍历。??为什么呢？
                //尝试将当前节点设置为同步等待队列的尾节点。???如果再不成功怎么办？--->是无限循环的尴尬

                // 下面几行，和上一个方法 addWaiter 是一样的，
                // 只是这个套在无限循环里，反正就是将当前线程排到队尾，有线程竞争的话排不上重复排
                if (compareAndSetTail(t, node)) {
                    /**
                     * 如果成功，将之前尾节点的后继节点指向当前节点(现
                     */
                    t.next = node;
                    /**
                     * 返回之前的尾节点。
                     */
                    return t;
                }
            }
        }
    }

    /**
     * Creates and enqueues node for current thread and given mode.
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */


    // 此方法的作用是把线程包装成node，同时进入到队列中
    // 参数mode此时是Node.EXCLUSIVE，代表独占模式
    private Node addWaiter(Node mode) {
        /**
         * 根据当前线程和模式创建一个Node。
         */
        Node node = new Node(Thread.currentThread(), mode);
        // Try the fast path of enq; backup to full enq on failure
        /**
         * 尝试快速入队，失败的话再执行正常的入队过程
         */
        // 以下几行代码想把当前node加到链表的最后面去，也就是进到阻塞队列的最后
        // tail!=null => 队列不为空(tail==head的时候，其实队列是空的，不过不管这个吧)

        Node pred = tail;
        if (pred != null) {
            /**
             * 如果同步等待队列尾节点不为null,将当前(线程的)Node链接到未接到尾节点
             */
            //这里有个问题，如果尾节点刚好执行完，要出队列怎么办？？？？
            // 设置自己的前驱 为当前的队尾节点
            node.prev = pred;
            /**
             * 尝试将当前Node设置(原子操作)为同步等待队列的尾节点。
             */
            // 用CAS把自己设置为队尾, 如果成功后，tail == node了
            if (compareAndSetTail(pred, node)) {
                /**
                 *  如果设置成功，完成链接(pred的next指向当前节点)。
                 */
                // 进到这里说明设置成功，当前node==tail, 将自己与之前的队尾相连，
                // 上面已经有 node.prev = pred
                // 加上下面这句，也就实现了和之前的尾节点双向连接了
                pred.next = node;
                /**
                 *  返回当前节点。
                 */
                // 线程入队了，可以返回了
                return node;
            }
        }
        /**
         *  如果同步等待队列尾节点为null，或者快速入队过程中设置尾节点失败，进行正常的入队过程，调用enq方法。
         */
        // 仔细看看上面的代码，如果会到这里，
        // 说明 pred==null(队列是空的) 或者 CAS失败(有线程在竞争入队)
        enq(node);
        return node;
    }

    /**
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * @param node the node
     */
    private void setHead(Node node) {
        head = node;
        /**
         * 请求成功，当前线程获取控制权，当前节点会取代之前(dummy)头节点的位置。所以置空thread和prev这些没用的域。
         */
        node.thread = null;
        node.prev = null;
    }

    /**
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     */
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */

        /**
         *
         * 如果node的等待状态为负数(比如:可能需要一个信号)，尝试去清空
         * "等待唤醒"的状态(将状态置为0)，即使设置失败，或者该状态已经
         * 被正在等待的线程修改，也没有任何影响。
         */

        int ws = node.waitStatus;
        if (ws < 0) //如果当前节点的状态小于0，尝试设置为0。
            compareAndSetWaitStatus(node, ws, 0);

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         */

        /**
         *
         * 需要唤醒的线程在node的后继节点，一般来说就是node的next引用指向的节点。
         * 但如果next指向的节点被取消或者为null，那么就同步等待队列的队尾反向查找离
         * 当前节点最近的且状态不是"取消"的节点。
         */

        // 下面的代码就是唤醒后继节点，但是有可能后继节点取消了等待（waitStatus==1）
        // 从队尾往前找，找到waitStatus<=0的所有节点中排在最前面的
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {
            s = null;
            // 从后往前找，仔细看代码，不必担心中间有节点取消(waitStatus==1)的情况??不太明白
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        if (s != null) //如果存在(需要唤醒的节点)，将该节点的线程唤醒
            LockSupport.unpark(s.thread);
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */

    /**
     * 共享模式下的释放(控制权)动作 -- 唤醒后继节点并保证传递。
     * 注:在独占模式下，释放仅仅意味着如果有必要，唤醒头节点的
     * 后继节点。
     */

    // 调用这个方法的时候，state == 0
    // 这个方法先不要看所有的代码，按照思路往下到我写注释的地方，其他的之后还会仔细分析
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
    /*
     * 保证释放动作(向同步等待队列尾部)传递，即使没有其他正在进行的
     * 请求或释放动作。如果头节点的后继节点需要唤醒，那么执行唤
     * 动作；如果不需要，将头结点的等待状态设置为PROPAGATE保证
     * 唤醒传递。另外，为了防止过程中有新节点进入(队列)，这里必
     * 需做循环，所以，和其他unparkSuccessor方法使用方式不一样
     * 的是，如果(头结点)等待状态设置失败，重新检测。
     */
        for (;;) {
            Node h = head;
            //判断同步等待队列是否为空

            // 1. h == null: 说明阻塞队列为空
            // 2. h == tail: 说明头结点可能是刚刚初始化的头节点，
            //   或者是普通线程节点，但是此节点既然是头节点了，那么代表已经被唤醒了，阻塞队列没有其他节点了
            // 所以这两种情况不需要进行唤醒后继节点
            if (h != null && h != tail) {
                //如果不为空，获取头节点的等待状态。
                int ws = h.waitStatus;
                //如果等待状态是SIGNAL，说明其后继节点需要唤醒
                //尝试修改等待状态

                // t3 入队的时候，已经将头节点的 waitStatus 设置为 Node.SIGNAL（-1） 了
                // t4 将头节点(此时是 t3)的 waitStatus 设置为 Node.SIGNAL（-1） 了
                if (ws == Node.SIGNAL) {
                    //如果修改失败，重新循环检测
                    // 这里 CAS 失败的场景请看下面的解读？？？详情见最后的注解


                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;
                    // loop to recheck cases
                    //如果修改成功，唤醒头节点的后继节点

                    // 就是这里，唤醒 head 的后继节点，也就是阻塞队列中的第一个节点
                    // 在这里，也就是唤醒 t3-->然后t4
                    unparkSuccessor(h);
                }
                //如果等待状态是0，尝试将其(头节点)设置为PROPAGATE
                // 这个 CAS 失败的场景是：执行到这里的时候，刚好有一个节点入队，入队会将这个 ws 设置为 -1
                else if (ws == 0 && !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
                // 如果设置失败，继续循环检测。
            }
            // 如果到这里的时候，前面唤醒的线程已经占领了 head，那么再循环
            // 否则，就是 head 没变，那么退出循环，
            // 退出循环是不是意味着阻塞队列中的其他节点就不唤醒了？当然不是，唤醒的线程之后还是会调用这个方法的

            /*h == head：说明头节点还没有被刚刚用 unparkSuccessor 唤醒的线程（这里可以理解为 t4）占有，此时 break 退出循环。
            h != head：头节点被刚刚唤醒的线程（这里可以理解为 t4）占有，那么这里重新进入下一轮循环，唤醒下一个节点（这里是 t4 ）。
            我们知道，等到 t4 被唤醒后，其实是会主动唤醒 t5、t6、t7...，那为什么这里要进行下一个循环来唤醒 t5 呢？我觉得是出于吞吐量的考虑。
            满足上面的 2 的场景，那么我们就能知道为什么上面的 CAS 操作 compareAndSetWaitStatus(h, Node.SIGNAL, 0) 会失败了？
            因为当前进行 for 循环的线程到这里的时候，可能刚刚唤醒的线程 t4 也刚刚好到这里了，那么就有可能 CAS 失败了。
            for 循环第一轮的时候会唤醒 t4，t4 醒后会将自己设置为头节点，如果在 t4 设置头节点后，for 循环才跑到 if (h == head)，
            那么此时会返回 false，for 循环会进入下一轮。t4 唤醒后也会进入到这个方法里面，那么 for 循环第二轮和 t4 就有可能在这个
             CAS 相遇，那么就只会有一个成功了。*/
            if (h == head)                   // loop if head changed
                break;
            // 如果过程中头节点没有发生变化，循环退出；否则需要继续检测。
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     *
     * 将node设置为同步等待队列的头节点，并且检测一下node的后继节点是
     * 否在共享模式下等待，如果是，并且propagate > 0 或者之前头节
     * 点的等待状态是PROPAGATE，唤醒后续节点。
     *
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        /**
         *  尝试去唤醒队列中的下一个节点，如果满足如下条件：
         *  调用者明确表示"传递"(propagate > 0),
         *  或者h.waitStatus为PROPAGATE(被上一个操作设置)
         *  (注:这里使用符号检测是因为PROPAGATE状态可能会变成SIGNAL状态)
         *  并且下一个节点处于共享模式或者为null。
         *
         */
        // 下面说的是，唤醒当前 node 之后的节点，即 t3 已经醒了，马上唤醒 t4
        // 类似的，如果 t4 后面还有 t5，那么 t4 醒了以后，马上将 t5 给唤醒了
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared())
                // 又是这个方法，只是现在的 head 已经不是原来的空节点了，是 t3 的节点了
                doReleaseShared();
        }
    }

    // Utilities for various versions of acquire

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;

        /**
         * 跳过首先将要取消的节点的thread域置空。
         */
        node.thread = null;

        // Skip cancelled predecessors
        /**
         *  跳过状态为"取消"的前驱节点
         */
        Node pred = node.prev;
        /**
         * node前面总是会存在一个非"取消"状态的节点，所以这里不需要null检测。
         */
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.

        // predNext节点(node节点前面的第一个非取消状态节点的后继节点)是需要"断开"的节点。
        // 下面的CAS操作会达到"断开"效果，但(CAS操作)也可能会失败，因为可能存在其他"cancel"
        // 或者"singal"的竞争
        Node predNext = pred.next;

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        node.waitStatus = Node.CANCELLED;

        // If we are the tail, remove ourselves.
        /**
         * 如果当前节点是尾节点，那么删除当前节点(将当前节点的前驱节点设置为尾节点)。
         */
        if (node == tail && compareAndSetTail(node, pred)) {
            compareAndSetNext(pred, predNext, null);
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.

            // 如果当前节点不是尾节点，说明后面有其他等待线程，需要做一些唤醒工作。
            // 如果当前节点不是头节点，那么尝试将当前节点的前驱节点
            // 的等待状态改成SIGNAL，并尝试将前驱节点的next引用指向
            // 其后继节点。否则，唤醒后继节点。
            int ws;
            if (pred != head &&
                ((ws = pred.waitStatus) == Node.SIGNAL ||
                 (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                pred.thread != null) {
                //如果当前节点的前驱节点不是头节点，那么需要给当前节点的后继节点一个"等待唤醒"的标记，
                //即 将当前节点的前驱节点等待状态设置为SIGNAL，然后将其设置为当前节点的后继节点的前驱节点....(真绕!)
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
                //否则，唤醒当前节点的后继节点。
                unparkSuccessor(node);
            }

            //前面提到过，取消节点的next引用会指向自己。
            node.next = node; // help GC
        }
    }

    /**
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     *
     * 在一个节点请求失败时，检测并更新改节点的(等待)状态。如果当前
     * 节点的线程应该被阻塞，那么返回true。这里是整个请求(循环)中主
     * 要信号控制部分。方法的条件：pred == node.prev
     */

    // 刚刚说过，会到这里就是没有抢到锁呗，这个方法说的是："当前线程没有抢到锁，是否需要挂起当前线程？"
    // 第一个参数是前驱节点，第二个参数才是代表当前线程的节点
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {

        /**
         * 获取当前节点的前驱节点的等待状态，如果status>0表明这个节点被取消了
         */
        int ws = pred.waitStatus;
        /**
         * 如果当前节点的前驱节点的状态为SIGNAL，说明当前节点已经声明了需要唤醒，
         * 所以可以阻塞当前节点了，直接返回true。
         * 一个节点在其被阻塞之前需要线程"声明"一下其需要唤醒(就是将其前驱节点
         * 的等待状态设置为SIGNAL，注意其前驱节点不能是取消状态，如果是，要跳过)
         */

        // 前驱节点的 waitStatus == -1 ，说明前驱节点状态正常，当前线程需要挂起，直接可以返回true
        // 第一次进来一般不会为-1，因为前驱节点的状态是后继节点设置的，但是调用当前方法的方法是循环调用的，后面会为-1
        if (ws == Node.SIGNAL)
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true;

        /**
         *
         * 如果当前节点的前驱节点是取消状态，那么需要跳过这些(取消状态)前驱节点
         * 然后重试。
         */

        // 前驱节点 waitStatus大于0 ，之前说过，大于0 说明前驱节点取消了排队。这里需要知道这点：
        // 进入阻塞队列排队的线程会被挂起，而唤醒的操作是由前驱节点完成的。
        // 所以下面这块代码说的是将当前节点的prev指向waitStatus<=0的节点，
        // 简单说，就是为了找个好爹，因为你还得依赖它来唤醒呢，如果前驱节点取消了排队，
        // 找前驱节点的前驱节点做爹，往前循环总能找到一个好爹的
        if (ws > 0) {
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             */
            /**
             * 这里等待状态一定是0或者PROPAGATE。这里将当前节点的前驱节点(非取消状态)的
             * 等待状态设置为SIGNAL。来声明需要一个(唤醒)信号。接下来方法会返回false，
             * 还会继续尝试一下请求，以确保在阻塞之前确实无法请求成功。
             */
            // 仔细想想，如果进入到这个分支意味着什么
            // 前驱节点的waitStatus不等于-1和1，那也就是只可能是0，-2，-3
            // 在我们前面的源码中，都没有看到有设置waitStatus的，所以每个新的node入队时，waitStatu都是0
            // 用CAS将前驱节点的waitStatus设置为Node.SIGNAL(也就是-1)
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */

    //如果我们要取消一个线程的排队，我们需要在另外一个线程中对其进行中断。比如某线程调用 lock() 老久不返回，我想中断它。
    //一旦对其进行中断，此线程会从 LockSupport.park(this) 中唤醒，然后 Thread.interrupted(); 返回 true。
    private final boolean parkAndCheckInterrupt() {
        /**
         * 阻塞当前线程。
         */
        LockSupport.park(this);
        /**
         * 线程被唤醒，方法返回当前线程的中断状态，并重置当前线程的中断状态（设置为false）
         */
        return Thread.interrupted();
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    // 下面这个方法，参数node，经过addWaiter(Node.EXCLUSIVE)，此时已经进入阻塞队列
    // 注意一下：如果acquireQueued(addWaiter(Node.EXCLUSIVE), arg))返回true的话，
    // 意味着上面这段代码将进入selfInterrupt()，所以正常情况下，下面应该返回false
    // 这个方法非常重要，应该说真正的线程挂起，然后被唤醒后去获取锁，都在这个方法里了

    //首先，到这个方法的时候，节点一定是入队成功的。
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                //找到当前节点的前驱节点p
                final Node p = node.predecessor();
                /**
                 * 检测p是否为头节点，如果是，再次调用tryAcquire方法
                 * (这里可以体现出acquire方法执行过程中tryAcquire方法
                 * 至少被调用一次)。
                 */

                // p == head 说明当前节点虽然进到了阻塞队列，但是是阻塞队列的第一个，因为它的前驱是head
                // 注意，阻塞队列不包含head节点，head一般指的是占有锁的线程，head后面的才称为阻塞队列
                // 所以当前节点可以去试抢一下锁
                // 这里我们说一下，为什么可以去试试：
                // 首先，它是阻塞队头，这个是第一个条件，其次，当前的head有可能是刚刚初始化的新的node，
                // enq(node) 方法里面有提到，head是延时初始化的，而且new Node()的时候没有设置任何线程
                // 也就是说，当前的head不属于任何一个线程，所以作为阻塞队头，可以去试一试，
                // tryAcquire已经分析过了, 忘记了请往前看一下，就是简单用CAS试操作一下state
                if (p == head && tryAcquire(arg)) {
                    /**
                     * 如果p节点是头节点且tryAcquire方法返回true。那么将
                     * 当前节点设置为头节点。
                     * 从这里可以看出，请求成功且已经存在队列中的节点会被设置为头节点
                     */
                    setHead(node);
                    //将p的next引用置空，帮助GC，现在p已经不再是头节点了，头结点一致再换
                    p.next = null; // help GC
                    //设置请求标记为成功
                    failed = false;
                    //传递中断状态，并返回。（为什么要传递中断状态？？就是将中断状态保持一下么？）
                    //循环唯一的出口
                    return interrupted;
                }
                //如果p节点不是头节点，或者tryAcquire返回false，说明请求失败。
                //那么首先需要判断请求失败后node节点是否应该被阻塞，如果应该
                //被阻塞，那么阻塞node节点，并检测中断状态。

                // 到这里，说明上面的if分支没有成功，要么当前node本来就不是队头，
                // 要么就是tryAcquire(arg)没有抢赢别人，继续往下看


              /*   private static boolean shouldParkAfterFailedAcquire(Node pred, Node node)
                 这个方法结束根据返回值我们简单分析下：
                 如果返回true, 说明前驱节点的waitStatus==-1，是正常情况，那么当前线程需要被挂起，等待以后被唤醒
                        我们也说过，以后是被前驱节点唤醒，就等着前驱节点拿到锁，然后释放锁的时候叫你好了
                 如果返回false, 说明当前不需要被挂起，为什么呢？往后看

                 跳回到前面是这个方法
                 if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt())
                                interrupted = true;

                 1. 如果shouldParkAfterFailedAcquire(p, node)返回true，
                 那么需要执行parkAndCheckInterrupt():

                 这个方法很简单，因为前面返回true，所以需要挂起线程，这个方法就是负责挂起线程的
                 这里用了LockSupport.park(this)来挂起线程，然后就停在这里了，等待被唤醒=======*/

                // 2. 接下来说说如果shouldParkAfterFailedAcquire(p, node)返回false的情况

                // 仔细看shouldParkAfterFailedAcquire(p, node)，我们可以发现，其实第一次进来的时候，一般都不会返回true的，
                // 原因很简单，前驱节点的waitStatus=-1是依赖于后继节点设置的。也就是说，我都还没给前驱设置-1呢，怎么可能是true呢，
                // 但是要看到，这个方法是套在循环里的，所以第二次进来的时候状态就是-1了。

                // 解释下为什么shouldParkAfterFailedAcquire(p, node)返回false的时候不直接挂起线程：
                // => 是为了应对在经过这个方法后，node已经是head的直接后继节点了。剩下的读者自己想想吧。
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt())
                    //如果有中断，设置中断状态。

                    /*我们发现一个问题，即使是中断唤醒了这个线程，也就只是设置了 interrupted = true 然后继续下一次循环。
                    而且，由于 Thread.interrupted() 会清除中断状态，第二次进 parkAndCheckInterrupt 的时候，返回会是 false。
                    所以，我们要看到，在这 个方法中，interrupted 只是用来记录是否发生了中断，然后用于方法返回值，其他没有做任
                    何相关事情。*/

                    //所以说，lock() 方法处理中断的方法就是，你中断归中断，我抢锁还是照样抢锁，几乎没关系，只是我抢到锁了以后，
                    //设置线程的中断状态而已，也不抛出任何异常出来。调用者获取锁后，可以去检查是否发生过中断，也可以不理会。
                    interrupted = true;
            }
        } finally {
            //最后检测一下如果请求失败(异常退出)，取消请求。
            if (failed) cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     * @param arg the acquire argument
     *       和前面的acquireQueued方法类似，区别基本上只是对中断状态的处理，这里没有将
     *       中断状态传递给上层，而是直接抛出InterruptedException异常，方法实现
     *       里其他方法的分析可以参考
     *
     */


    private void doAcquireInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                // 就是这里了，一旦异常，马上结束这个方法，抛出异常。
                // 这里不再只是标记这个方法的返回值代表中断状态
                // 而是直接抛出异常，而且外层也不捕获，一直往外抛到 lockInterruptibly
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            // 如果通过 InterruptedException 异常出去，那么 failed 就是 true 了
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     *
     *  和前面的doAcquireInterruptibly方法类似，区别在于方法实现里面加入了超时时间的检测，
     *  如果超时方法返回false。阻塞部分较之前也有区别，如果剩余的超时时间小于1000纳秒，方法
     *  自旋；否则当前线程阻塞一段时间(剩余超时时间时长)。方法实现里其他方法的分析可以参考前面。
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared uninterruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        //将当前线程以共享模式加入同步等待队列。
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            //请求主循环
            for (;;) {
                //获取当前节点的前驱节点p
                final Node p = node.predecessor();
                //如果p是头节点。再次调用tryAcquireShared方法。
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        //如果tryAcquireShared方法执行成功，执行setHeadAndPropagate（）

                        //唤醒线程的时候会进入这里-->t3 会进到 setHeadAndPropagate(node, r) 这个方法，先把 head 给占了，然后唤醒队列中其他的线程
                        setHeadAndPropagate(node, r);
                        //p节点被移除，置空next引用，帮助GC
                        p.next = null; // help GC
                        //检测中断状态，传递中断状态
                        if (interrupted)
                            selfInterrupt();
                        //标记方法请求成功。
                        failed = false;
                        return;
                    }
                }
                //如果当前节点的前驱节点不是头节点，判断当前节点
                //请求失败后是否要被阻塞，如果是，阻塞并保存当前线程中断状态。
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            //如果请求失败，取消当前节点。
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     * 和doAcquireShared方法基本一致，唯一区别就是没有传递线程中断状态，而是直接抛出异常
     *
     * 从方法名我们就可以看出，这个方法是获取共享锁，并且此方法是可中断的（中断的时候抛出 InterruptedException 退出这个方法）。
     *
     */
    private void doAcquireSharedInterruptibly(int arg)
        throws InterruptedException {
        // 1. 入队
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    // 同上，只要 state 不等于 0，那么这个方法返回 -1
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                //进入parkAndCheckInterrupt 的时候，t3 挂起。
                //我们再分析 t4 入队，t4 会将前驱节点 t3 所在节点的 waitStatus 设置为 -1，t4 入队后，应该是这样的
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     *
     *  和前面的doAcquireSharedInterruptibly方法类似，区别在于方法实现里面加入了超
     *  时时间的检测，如果超时方法返回false。阻塞部分较之前也有区别，如果剩余的超时时
     *  间小于1000纳秒，方法自旋；否则当前线程阻塞一段时间(剩余超时时间时长)。方法实
     *  现里其他方法的分析可以参考前面。
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */

    /**
     * 在独占模式下尝试请求(控制权)。这个方法(实现)应该查看一下对象的
     * 状态是否允许在独占模式下请求，如果允许再进行请求。
     *
     * 这个方法总是被请求线程执行，如果方法执行失败，会将当前线程放到
     * 同步等待队列中(如果当前线程还不在同步等待队列中)，直到被其他线程的释放
     * 操作唤醒。可以用来实现Lock的tryLock方法。
     * @param arg
     * @return
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     *
     * 该方法并没有具体实现，而是交给子类去实现：
     * 尝试设置(AQS的)状态，反映出独占模式下的一个释放动作。
     * 这个方法在线程释放(控制权)的时候被调用。
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     *
     *
     * 在共享模式下尝试请求(控制权)。这个方法(实现)应该查看一下对象的
     * 状态是否允许在共享模式下请求，如果允许再进行请求。
     *
     * 这个方法总是被请求线程执行，如果方法执行失败，会将当前线程放到
     * 同步等待队列中(如果当前线程还不在同步等待队列中)，直到被其他线程的释放
     * 操作唤醒。
     *
     *  返回负数表示失败；返回0表示共享模式下的请求成功，但是接下来
     *         的共享模式请求不会成功；返回正数表示共享模式请求成功，接下来
     *         的共享模式请求也可以成功，当然前提是接下来的等待线程必须检测
     *         对象的状态是否允许请求。
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */

    /**
     * 独占模式下进行请求，忽略中断。方法实现中至少会调用一次tryAcquire方法，
     * 请求成功后方法返回。否则当前线程会排队，可能会重复的阻塞和解除阻塞，
     * 执行tryAcquire方法，直到成功。这个方法可以用来实现Lock的lock方法。
     *
     * @param arg the acquire argument.  这个值被传递给tryAcquire方法，值在
     *        这里并没有实际意义，如果基于AQS实现自己的同步机制(可能要实现
     *        tryAcquire方法)，可以灵活利用这个值。
     *
     * ------------------------------
     *  acquire方法中首先调用tryAcquire方法，如果tryAcquire返回true，说明请求成
     *  功，直接返回；否则，继续调用acquireQueued方法，如果acquireQueued方法返
     *  回true，还需要调用一下selfInterrupt方法。首先看一下tryAcquire方法，该方
     *  法在AQS中并没有具体实现，而是开放出来
     *
     *  ========================
     *  1.调用tryAcquire方法进行(控制权)请求，如果请求成功，方法直接返回。
     *  2.如果请求失败，那么会使用当前线程建立一个独占模式的节点，然后将节点放到同步等待队列的队尾。
     *            然后进入一个无限循环。(这个过程中会帮助完成同步等待队列的初始化，初始化过程中也
     *            可以看到，同步等待队列初始化后头尾节点都指向同一个哑节点。请求失败的线程(节点)进
     *            入队列时会链接到队列的尾部，如果同步等待队列内的线程(节点)请求成功，会将其设置为
     *            新的头节点。)
     *  3.无限循环中会判断当前同步等待队列中是否有其他线程。
     *  4. 如果没有，再次调用tryAcquire进行请求。
     *  5.如果请求成功，将当前节点设置为同步等待队列头节点，向上传递中断状态，然后主循环退出。
     *  6.如果同步等待队列中有其他线程(在当前线程前面)，或者前面第4步请求失败，那么首先需要检查当前节
     *            点是否已经设置"等待唤醒"标记，即将其非取消状态前驱节点的等待状态设置为SIGNAL。
     *  7.如果未设置"等待唤醒"标记，进行标记设置，然后继续进行无限循环，进入第3步。
     *  8.如果已经设置"等待唤醒"标记，那么阻塞当前线程(节点)。
     *  10.最后在无限循环退出后，要判断请求是否失败(由于一些原因，循环退出，但请求失败)，如果失败，取
     *            消当前节点。
     *
     *
     *
     */


    // 我们看到，这个方法，如果tryAcquire(arg) 返回true, 也就结束了。
    // 否则，acquireQueued方法会将线程压到队列中
    // 此时 arg == 1
    public final void acquire(int arg) {
        // 首先调用tryAcquire(1)一下，名字上就知道，这个只是试一试
        // 因为有可能直接就成功了呢，也就不需要进队列排队了，
        // 对于公平锁的语义就是：本来就没人持有锁，根本没必要进队列等待(又是挂起，又是等待被唤醒的)
        //--------------------------------------------------------------------------------
        // tryAcquire(arg)没有成功，这个时候需要把当前线程挂起，放到阻塞队列中。

        // 假设tryAcquire(arg) 返回false，那么代码将执行：
        //        acquireQueued(addWaiter(Node.EXCLUSIVE), arg)，
        // 这个方法，首先需要执行：addWaiter(Node.EXCLUSIVE)

        if (!tryAcquire(arg) && acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     *
     * 独占模式下，响应中断的请求方法，这个方法会抛出中断异常
     *
     * 独占模式下进行请求，如果当前线程被中断，放弃方法执行(抛出异常)，
     * 方法实现中，首先会检查当前线程的中断状态，然后会执行至少一次
     * tryAcquire方法，如果请求成功，方法返回；如果失败，当前线程会。
     * 在同步等待队列中排队，可能会重复的被阻塞和被唤醒，并执行tryAcquire
     * 方法直到成功或者当前线程被中断。可以用来实现Lock的lockInterruptibly。
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))//如果请求不成功，执行doAcquireInterruptibly方法。
            doAcquireInterruptibly(arg);
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     *
     * 独占模式下，响应中断并且支持超时的请求方法：
     * 独占模式下进行请求，如果当前线程被中断，放弃方法执行(抛出异常)，
     * 如果给定的超时时间耗尽，方法失败。方法实现中，首先会检查当前线程
     * 的中断状态，然后会执行至少一次tryAcquire方法，如果请求成功，方法
     * 返回；如果失败，当前线程会在同步等待队列中排队，可能会重复的被阻塞和
     * 被唤醒，并执行tryAcquire方法直到成功或者当前线程被中断或者超时时
     * 间耗尽。可以用来实现Lock的tryLock(long, TimeUnit)。
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
            doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     *
     * 独占模式下的释放方法。方法实现中，如果tryRelease返回true，会唤醒
     * 一个或者多个线程。这个方法可以用来实现Lock的unlock方法。
     */
    public final boolean release(int arg) {
       //方法中首先调用tryRelease。如果调用成功，继续判断同步等待队列里是否有需要唤醒的线程，如果有，进行唤醒。
        if (tryRelease(arg)) {
            Node h = head;
            if (h != null && h.waitStatus != 0)
                // 唤醒后继节点
                // 从上面调用处知道，参数node是head头结点
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     *
     *  分析共享模式下的请求方法。首先看下忽略中断的请求方法
     *  acquireShared方法中首先调用tryAcquireShared方法，如果tryAcquireShared返回值大于等于0，
     *   说明请求成功，直接返回；否则，继续调用doAcquireShared方法。
     *  先看一下tryAcquireShared方法，该方法在AQS中并没有具体实现，同样开放出来，交由子类去实现。
     *  ==================================================
     *  1.调用tryAcquireShared方法进行(控制权)请求，如果请求成功，方法直接返回。
     *  2.如果请求失败，那么会使用当前线程建立一个共享模式的节点，然后将节点放到同步等待队列的队尾。
     *            然后进入一个无限循环。
     *  3.无限循环中会判断当前同步等待队列中是否有其他线程。
     *  4.如果没有，再次调用tryAcquireShared进行请求。
     *  5.如果请求成功，将当前节点设置为同步等待队列头节点，同时检查是否需要继续唤醒下一个共享模式
     *            节点，如果需要就继续执行唤醒动作。当然还会向上传递中断状态，然后主循环退出。
     *  6.如果同步等待队列中有其他线程(在当前线程前面)，或者第4步的请求失败，那么首先需要检查当前节
     *            点是否已经设置"等待唤醒"标记，即将其非取消状态前驱节点的等待状态设置为SIGNAL。
     *  7.如果未设置"等待唤醒"标记，进行标记设置，然后继续进行无限循环，进入第3步。
     *  8.如果已经设置"等待唤醒"标记，那么阻塞当前线程(节点)。
     *  9.当前节点(线程)被唤醒后，设置(传递)中断标记，然后继续进行无限循环，进入第3步。
     * 10.最后在无限循环退出后，要判断请求是否失败(由于一些原因，循环退出，但请求失败)，如果失败，取消当前节点。
     *
     *
     */
    public final void acquireShared(int arg) {
        //首先调用tryAcquireShared方法
        if (tryAcquireShared(arg) < 0)
            //如果tryAcquireShared方法返回结果小于0，继续调用doAcquireShared方法
            doAcquireShared(arg);
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     *
     *
     * 共享模式下，响应中断的请求方法，这个方法会抛出中断异常：
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        //如果当前线程被中断，抛出中断异常。
        if (Thread.interrupted())
            throw new InterruptedException();
        //首先调用tryAcquireShared请求方法，请求失败的话，继续调用doAcquireSharedInterruptibly方法。


        //线程调用 await 的时候，state 都大于 0。
        // 也就是说，这个 if 返回 true，然后往里看

        // 只有当 state == 0 的时候，这个方法才会返回 1，否者返回-1
        //        protected int tryAcquireShared(int acquires) {
        //            return (getState() == 0) ? 1 : -1;
        //        }
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     *
     * 共享模式下，响应中断并且支持超时的请求方法：
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        //如果当前线程被中断，抛出中断异常。
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 || //首先调用tryAcquireShared请求方法，请求失败的话，继续调用doAcquireSharedNanos方法。
            doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     *
     *  共享模式下的释放方法。方法实现中，如果tryReleaseShared方法
     * 返回true，那么会唤醒一个或者多个线程。
     *
     */
    public final boolean releaseShared(int arg) {
        // 只有当 state 减为 0 的时候，tryReleaseShared 才返回 true
        // 否则只是简单的 state = state - 1 那么 countDown 方法就结束了
        if (tryReleaseShared(arg)) {
            // 唤醒 await 的线程
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     *
     * 查询同步等待队列中是否有线程在等待(请求控制权)。
     * 注：因为由中断和超时引起的取消随时会发生，所以此方法并不能保证
     * 结果准确。
     *
     * 方法时间复杂度为常数时间。
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     *
     *
     *
     * 查询是否有线程竞争发生，也就是说是否有请求发生过阻塞。
     *
     * 方法时间复杂度为常数时间。
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     *
     * 返回同步等待队列中第一个(最前面)线程，如果没有，返回空。
     *
     * 正常情况下，方法的时间复杂度为常数时间；如果发生竞争
     * 会有一些迭代过程。
     *
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay

        //先简单判断一下队列中是否有线程，没有的话，直接返回null；否则，调用fullGetFirstQueuedThread方法。
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
    /*
     * 通常情况下，头结点的next指向的就是队列里第一个节点。
     * 尝试获取第一个节点的线程域，保证读取的一致性：如果
     * 线程域为null，或者第一个节点的前驱节点已经不是头节
     * 点，那么说有其他线程正在调用setHead方法。这里尝试
     * 获取(比较)两次，如果获取失败，再进行下面的遍历。
     */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null) ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */
     /*
     * 头结点的next域可能还没有设置，或者已经在setHead后被重置。
     * 所以我们必须验证尾节点是否是真的是第一个节点。如果不是，
     * 如果不是，从尾节点反向遍历去查找头结点，确保程序退出。
     */
        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     *
     * 判断当前线程是否在同步等待队列中。
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();

        //反向遍历同步等待队列，查找给定线程是否存在。
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     *
     *
     * 如果同步等待队列中第一个线程是独占模式，返回true。
     * 如果这个方法返回true，并且当前线程正尝试在共享模式下请求，那么可
     * 以保证当前线程不是同步等待队列里的第一个线程。
     */

    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
            (s = h.next)  != null &&
            !s.isShared()         &&
            s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     *
     * 这个方法主要用来避免"插队"问题。
     * 判断同步等待队列里面是否存在比当前线程更早的线程。
     *
     * 相当于调用如下代码：
     * getFirstQueuedThread() != Thread.currentThread() && hasQueuedThreads()
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        //1.头和尾不相等说明肯定有队列
        //2.头和尾相等，就要判断头的next是不是为null（为null就还没有排到队列），（不为null就说明有队列了）或者next节点是否持有锁（多线程的原因，如果next节点不持有锁，说明有队列，否则重新判断）
        return h != t && ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     *
     *  获取当前同步等待队列中线程的(估计)数量。
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     *
     * 获取当前正在同步等待队列中等待的线程(不精确)。
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     *
     * * 获取当前正在同步等待队列中以独占模式进行等待的线程(不精确)。
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     *
     * 获取当前正在同步等待队列中以共享模式进行等待的线程(不精确)。
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     *
     * 如果一个node最初放在一个条件队列里，而现在正在AQS的同步等待队列里，
     * 返回true。
     */

    // 在节点入条件队列的时候，初始化时设置了 waitStatus = Node.CONDITION
    // 前面我提到，signal 的时候需要将节点从条件队列移到阻塞队列，
    // 这个方法就是判断 node 是否已经移动到阻塞队列了
    final boolean isOnSyncQueue(Node node) {
        // 移动过去的时候，node 的 waitStatus 会置为 0，这个之后在说 signal 方法的时候会说到
        // 如果 waitStatus 还是 Node.CONDITION，也就是 -2，那肯定就是还在条件队列中
        // 如果 node 的前驱 prev 指向还是 null，说明肯定没有在 阻塞队列
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        // 如果 node 已经有后继节点 next 的时候，那肯定是在阻塞队列了
        if (node.next != null) // If has successor, it must be on queue//如果有后继节点，说明肯定在AQS同步等待队列中
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
    /*
     * 之前的代码中分析到过，node.prev不为空并不能说明节点在AQS的
     * 同步等待队列里面，因为后续的CAS操作可能会失败，所以这里从尾节
     * 开始反向遍历。
     */
        // 这个方法从阻塞队列的队尾开始从后往前遍历找，如果找到相等的，说明在阻塞队列，否则就是不在阻塞队列

        // 可以通过判断 node.prev() != null 来推断出 node 在阻塞队列吗？答案是：不能。
        // 这个可以看上篇 AQS 的入队方法，首先设置的是 node.prev 指向 tail，
        // 然后是 CAS 操作将自己设置为新的 tail，可是这次的 CAS 是可能失败的。

        // 调用这个方法的时候，往往我们需要的就在队尾的部分，所以一般都不需要完全遍历整个队列的
        return findNodeFromTail(node);
    }

    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     */
    // 从同步队列的队尾往前遍历，如果找到，返回 true
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     *
     * 将一个节点从条件等待队列转移到同步等待队列。
     * 如果成功，返回true。
     */
    // 将节点从条件队列转移到阻塞队列
    // true 代表成功转移
    // false 代表在 signal 之前，节点已经取消了
    final boolean transferForSignal(Node node) {
        /*
         * If cannot change waitStatus, the node has been cancelled.
         */

        /*
         * 如果设置等待状态失败，说明节点已经被取消了，直接返回false。
         */
        // CAS 如果失败，说明此 node 的 waitStatus 已不是 Node.CONDITION，说明节点已经取消，
        // 既然已经取消，也就不需要转移了，方法返回，转移后面一个节点
        // 否则，将 waitStatus 置为 0
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        //将node加入到AQS同步等待队列中，并返回node的前驱节点。
        // enq(node): 自旋进入阻塞队列的队尾
        // 注意，这里的返回值 p 是 node 在阻塞队列的前驱节点
        Node p = enq(node);
        int ws = p.waitStatus;
        //如果前驱节点被取消，或者尝试设置前驱节点的状态为SIGNAL(表示node节点需要唤醒)失败，那么唤醒node节点上的线程。

        // ws > 0 说明 node 在阻塞队列中的前驱节点取消了等待锁，直接唤醒 node 对应的线程。???(为什么？)
        // 唤醒之后会怎么样，后面再解释
        // 如果 ws <= 0, 那么 compareAndSetWaitStatus 将会被调用，上篇介绍的时候说过，
        // 节点入队后，需要把前驱节点的状态设为 Node.SIGNAL(-1)

       /* 正常情况下，ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL) 这句中，ws <= 0，而且
        compareAndSetWaitStatus(p, ws, Node.SIGNAL) 会返回 true，所以一般也不会进去 if 语句块中唤醒
        node 对应的线程。然后这个方法返回 true，也就意味着 signal 方法结束了，节点进入了阻塞队列。

        假设发生了阻塞队列中的前驱节点取消等待，或者 CAS 失败，只要唤醒线程，让其进到下一步即可。*/
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            // 如果前驱节点取消或者 CAS 失败，会进到这里唤醒线程，之后的操作看下一节
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     *
     *  * 在取消等待后，将节点转移到同步队列中。如果线程在唤醒钱被
     * 取消，返回true。
     */

    // 只有线程处于中断状态，才会调用此方法
    // 如果需要的话，将这个已经取消等待的节点转移到阻塞队列
    // 返回 true：如果此线程在 signal 之前被取消，
    final boolean transferAfterCancelledWait(Node node) {
        // 用 CAS 将节点状态设置为 0
        // 如果这步 CAS 成功，说明是 signal 方法之前发生的中断，因为如果 signal 先发生的话，
        // signal 中会将 waitStatus 设置为 0
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            // 将节点放入阻塞队列
            // 这里我们看到，即使中断了，依然会转移到阻塞队列
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        // 到这里是因为 CAS 失败，肯定是因为 signal 方法已经将 waitStatus 设置为了 0
        // signal 方法会将节点转移到阻塞队列，但是可能还没完成，这边自旋等待其完成
        // 当然，这种事情还是比较少的吧：signal 调用之后，没完成转移之前，发生了中断
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
        /*这里再说一遍，即使发生了中断，节点依然会转移到阻塞队列。

        到这里，大家应该都知道这个 while 循环怎么退出了吧。要么中断，要么转移成功。*/
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait
     * @return previous sync state
     */

    /**
     * 调用release方法并传入当前的state。
     * 调用成功会返回传入release方法之前的state.
     * 失败会抛出异常，并取消当前节点。
     */

    // 首先，我们要先观察到返回值 savedState 代表 release 之前的 state 值
    // 对于最简单的操作：先 lock.lock()，然后 condition1.await()。
    //         那么 state 经过这个方法由 1 变为 0，锁释放，此方法返回 1
    //         相应的，如果 lock 重入了 n 次，savedState == n
    // 如果这个方法失败，会将节点设置为"取消"状态，并抛出异常 IllegalMonitorStateException
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            // 这里使用了当前的 state 作为 release 的参数，也就是完全释放掉锁，将 state 置为 0
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     *
     *   ConditionObject是AQS中提供的一种锁的基础机制，实现了接口Condition。
     *   Condition是一种类似于Object监视条件的一种机制，相对于Object来说，
     *   Condition能让线程在各自条件下的等待队列等待，而不是像Object一样，在同一个等待队列里面等待。
     *   Condition提供了await/signal/signalAll来支持与Object wait/notify/nofityAll类似的功能。
     *   Condition由Lock内建支持，使用起来会很方便，直接调用Lock的newCondition方法，便可以获得一个
     *   与其相关联的条件对象。
     *
     *    内部结构非常简单，也是链表结构，表示一个条件等待队列。(每个条件一个队列)
     *
     *
     *
     */

//    我们知道一个 ReentrantLock 实例可以通过多次调用 newCondition() 来产生多个 Condition 实例，
//    这里对应 condition1 和 condition2。注意，ConditionObject 只有两个属性 firstWaiter 和 lastWaiter；
//    每个 condition 有一个关联的条件队列，如线程 1 调用 condition1.await() 方法即可将当前线程 1 包装成
//    Node 后加入到条件队列中，然后阻塞在这里，不继续往下执行，条件队列是一个单向链表；
//    调用 condition1.signal() 会将condition1 对应的条件队列的 firstWaiter 移到阻塞队列的队尾，
//    等待获取锁，获取锁后 await 方法返回，继续往下执行。
//    我这里说的是最简单的流程，没有考虑中断、signalAll、还有带有超时参数的 await 方法等，不过把这里弄懂是这节的主要目的。
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** First node of condition queue. */

        // 条件队列的第一个节点
        // 不要管这里的关键字 transient，是不参与序列化的意思
        private transient Node firstWaiter;
        /** Last node of condition queue. */
        // 条件队列的最后一个节点
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * Adds a new waiter to wait queue.
         * @return its new wait node
         */
        // 将当前线程对应的节点入队，插入队尾
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            // 如果条件队列的最后一个节点取消了，将其清除出去
            if (t != null && t.waitStatus != Node.CONDITION) {
                // 这个方法会遍历整个条件队列，然后会将已取消的所有节点清除出队列
                /**
                 * 当 await 的时候如果发生了取消操作（这点之后会说），或者是在节点
                 * 入队的时候，发现最后一个节点是被取消的，会调用一次这个方法。
                 */
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            //创建一个当前线程对应的节点。
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            //如果是队列中第一个节点，那么将firstWaiter指向这个节点，后面也会将lastWaiter指向这个节点。
            // 如果队列为空
            if (t == null)
                firstWaiter = node;
            else
                //如果是队列中已经存在其他节点，那么将原本lastWaiter的nextWaiter指向当前节点
                t.nextWaiter = node;
            //最后将lastWaiter指向当前节点。
            lastWaiter = node;
            //返回当前节点。
            return node;
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         * @param first (non-null) the first node on condition queue
         */
        // 从条件队列队头往后遍历，找出第一个需要转移的 node
        // 因为前面我们说过，有些线程会取消排队，但是还在队列中
        private void doSignal(Node first) {
            do {
                //移除first
                // 将 firstWaiter 指向 first 节点后面的第一个
                // 如果将队头移除后，后面没有节点在等待了，那么需要将 lastWaiter 置为 null
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                // 因为 first 马上要被移到阻塞队列了，和条件队列的链接关系在这里断掉
                first.nextWaiter = null;
                //然后调用transferForSignal，如果调用失败且条件等待队列不为空，继续上面过程，否则方法结束
            } while (!transferForSignal(first) &&
                    // 这里 while 循环，如果 first 转移不成功，那么选择 first 后面的第一个节点进行转移，依此类推
                     (first = firstWaiter) != null);
        }

        /**
         * Removes and transfers all nodes.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            //首先将条件队列的头尾节点置空
            lastWaiter = firstWaiter = null;
            do {

                Node next = first.nextWaiter;
                first.nextWaiter = null;
                //移动first指向的节点，然后将first指向下一个节点，直到最后
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         *
         *
         */

        /**
         * 移除条件等待队列中的取消状态节点。这个方法一定是在持有锁
         * (拥有AQS控制权)的情况下被调用的(所以不存在竞争)。
         * 当等待条件时被(节点的线程)取消，或者当lastWaiter被取消后
         * 条件等待队列中进入了一个新节点时会调用这个方法。
         * 这个方法需要避免由于没有signal而引起的垃圾滞留。所以尽管
         * 方法内会做一个完全遍历，也只有超时获或取消时(没有signal的
         * 情况下)才被调用。方法中会遍历所有节点，切断所有指向垃圾节
         * 点的引用，而不是一次取消切断一个引用。
         */

        // 等待队列是一个单向链表，遍历链表将已经取消等待的节点清除出去
        // 纯属链表操作，很好理解，看不懂多看几遍就可以了
        private void unlinkCancelledWaiters() {
            //获取条件等待队列的头节点t
            Node t = firstWaiter;
            Node trail = null;

            while (t != null) {
                //如果队列中有等待节点。获取头节点的nextWaiter节点next。
                Node next = t.nextWaiter;
                // 如果节点的状态不是 Node.CONDITION 的话，这个节点就是被取消的
                if (t.waitStatus != Node.CONDITION) {
                    //如果t被取消。将t的nextWaiter置空。
                    t.nextWaiter = null;
                    if (trail == null)
                        //将next设置为头节点(移除之前的取消节点)
                        firstWaiter = next;
                    else
                        //否则说明队列前端有未取消的节点，这里做下拼接(移除中间的取消节点)
                        trail.nextWaiter = next;
                    if (next == null)
                        //最后设置尾节点。
                        lastWaiter = trail;
                }
                else
                    //如果t没被取消。将trail指向t。
                    trail = t;
                t = next;
            }
        }

        // public methods

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         *
         * 将条件等待队列里面等待时间最长(链表最前面)的线程(如果存在的话)
         * 移动到AQS同步等待队列里面。
         *
         */
        // 唤醒等待了最久的线程
        // 其实就是，将这个线程对应的 node 从条件队列转移到阻塞队列
        public final void signal() {
            // 调用 signal 方法的线程必须持有当前的独占锁
            //判断AQS的控制权是否被当前线程以独占的方式持有。如果不是，抛出IllegalMonitorStateException异常。
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                //如果有线程在条件队列里面等待，那么执行doSignal()方法
                doSignal(first);
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         *
         * 相对于signal方法，signalAll方法会将条件等待队列中全部线程都移动到AQS的同步等待队列中：
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                //与signal唯一区别是这里调用了doSignalAll()方法
                doSignalAll(first);
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         *
         * 不能中断的等待方法，awaitUninterruptibly方法
         *  awaitUninterruptibly的逻辑相对await来说更加明确，条件循环中如果线程被中断，直接退出。后续只需要传递中断状态即可。
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /** Mode meaning to reinterrupt on exit from wait */

        /** 在等待退出时重新中断(传递中断状态) */
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        /** 在等待退出时抛出异常 */
        private static final int THROW_IE    = -1;

        /**
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */

        /**
         * 线程唤醒后第一步是调用 checkInterruptWhileWaiting(node) 这个方法，
         * 此方法用于判断是否在线程挂起期间发生了中断，如果发生了中断，是 signal
         * 调用之前中断的，还是 signal 之后发生的中断。
         */
        private int checkInterruptWhileWaiting(Node node) {
            // 1. 如果在 signal 之前已经中断，返回 THROW_IE
            // 2. 如果是 signal 之后中断，返回 REINTERRUPT
            // 3. 没有发生中断，返回 0
            // Thread.interrupted()：如果当前线程已经处于中断状态，那么该方法返回 true，同时将中断状态重置为 false，
            // 所以，才有后续的 重新中断（REINTERRUPT） 的使用。
            return Thread.interrupted() ? (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) : 0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
//        我们终于可以好好说下这个 interruptMode 干嘛用了。
//
//               0：什么都不做。
//        THROW_IE：await 方法抛出 InterruptedException 异常
//        REINTERRUPT：重新中断当前线程
        private void reportInterruptAfterWait(int interruptMode)
            throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         *
         *
         * 先看一下支持中断的等待方法，await方法
         *
         *
         *      1.如果当前线程有中断状态，抛出InterruptedException异常。
         *      2.添加当前线程到条件等待队列。
         *      3.释放当前线程对AQS的控制权，并保存释放前AQS的状态(state域)。
         *      4.进入条件循环，条件为判断当前线程是否在AQS同步队列中，如果不在那么阻塞当前线程；
         *       如果在AQS同步队列中，就到第7步。
         *      5.当前线程被(其他线程)唤醒后，要检查等待过程中是否被中断或者取消，如果不是，继续循环，到第4步。
         *      6.如果是，保存中断状态和模式，然后退出条件循环。
         *      7.请求AQS控制权，然后做一些收尾工作，如果被取消，清理一下条件等待队列；然后按照中断模式处理
         *       一下中断。
         *
         *       ===================
         *          await就是把当前线程放到对应条件的等待队列里面，然后阻塞当前线程。
         *          signal就是把对应条件的等待队里的线程移动到对应AQS的同步等待队列里面，随后线程会被唤醒。

         *          注：await存在"伪唤醒"问题，所以被唤醒后应该再次检测等待条件：
         *          while(condition不满足) { conditionObject.await() }
         */

        // 首先，这个方法是可被中断的，不可被中断的是另一个方法 awaitUninterruptibly()
        // 这个方法会阻塞，直到调用 signal 方法（指 signal() 和 signalAll()，下同），或被中断
        public final void await() throws InterruptedException {
            //如果当前线程被中断，抛出InterruptedException异常。
            if (Thread.interrupted())
                throw new InterruptedException();
            //将当前线程添加到条件等待队列。
            // 添加到 condition 的条件队列中
            Node node = addConditionWaiter();
            //释放当前线程对AQS的控制权，并返回当前AQS中的state值。
            // 释放锁，返回值是释放锁之前的 state 值
            int savedState = fullyRelease(node);

            //释放掉锁以后，接下来是这段，这边会自旋，如果发现自己还没到阻塞队列，那么挂起，等待被转移到阻塞队列。
            int interruptMode = 0;
            // 这里退出循环有两种情况，之后再仔细分析
            // 1. isOnSyncQueue(node) 返回 true，即当前 node 已经转移到阻塞队列了
            // 2. checkInterruptWhileWaiting(node) != 0 会到 break，然后退出循环，代表的是线程中断
            while (!isOnSyncQueue(node)) {
                //如果当前线程不在AQS的同步等待队列中，那么阻塞当前线程(等待被唤醒进入aqs队列/////)
                LockSupport.park(this);
                //其他线程调用相同条件上的signal/signalALl方法时，会将这个节点从条件队列转义到AQS的同步等待队列中。
                //被唤醒后需要检查是否在等待过程中被中断。

                /*先解释下 interruptMode。interruptMode 可以取值为 REINTERRUPT（1），THROW_IE（-1），0

                REINTERRUPT： 代表 await 返回的时候，需要重新设置中断状态
                THROW_IE： 代表 await 返回的时候，需要抛出 InterruptedException 异常
                0 ：说明在 await 期间，没有发生中断
                有以下三种情况会让 LockSupport.park(this); 这句返回继续往下执行：

                1.常规路径。signal -> 转移节点到阻塞队列 -> 获取了锁（unpark）
                2.线程中断。在 park 的时候，另外一个线程对这个线程进行了中断
                3.signal 的时候我们说过，转移以后的前驱节点取消了，或者对前驱节点的CAS操作失败了
                4.假唤醒。这个也是存在的，和 Object.wait() 类似，都有这个问题
                线程唤醒后第一步是调用 checkInterruptWhileWaiting(node) 这个方法，此方法用于判断是否在
                线程挂起期间发生了中断，如果发生了中断，是 signal 调用之前中断的，还是 signal 之后发生的中断。*/
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    //如果发生了中断，退出循环。
                    break;
            }
            //重新请求AQS的控制权。
            // 被唤醒后，将进入阻塞队列，等待获取锁

            /**
             * 由于 while 出来后，我们确定节点已经进入了阻塞队列，准备获取锁。

             这里的 acquireQueued(node, savedState) 的第一个参数 node 之前已经经过
             enq(node) 进入了队列，参数 savedState 是之前释放锁前的 state，这个方法返
             回的时候，代表当前线程获取了锁，而且 state == savedState了。

             注意，前面我们说过，不管有没有发生中断，都会进入到阻塞队列，而 acquireQueued(node, savedState)
             的返回值就是代表线程是否被中断。如果返回 true，说明被中断了，而且 interruptMode != THROW_IE，说明在 signal
             之前就发生中断了，这里将 interruptMode 设置为 REINTERRUPT，用于待会重新中断。
             */
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
           /* 本着一丝不苟的精神，这边说说 node.nextWaiter != null 怎么满足。
           我前面也说了 signal 的时候会将节点转移到阻塞队列，有一步是
           node.nextWaiter = null，将断开节点和条件队列的联系。

            可是，在判断发生中断的情况下，是 signal 之前还是之后发生的？
            这部分的时候，我也介绍了
            ，如果 signal 之前就中断了，也需要将节点进行转移到阻塞队列，
            这部分转移的时候，是没有设置 node.nextWaiter = null 的。
            之前我们说过，如果有节点取消，也会调用 unlinkCancelledWaiters 这个方法，就是这里了*/
            if (node.nextWaiter != null) // clean up if cancelled
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                //如果上面发生过中断，这里处理中断。
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         *
         * 支持超时和中断的等待方法，awaitNanos和await(long time, TimeUnit unit)方法：
         *  和await相比，这两个方法只是加入了超时取消的机制。
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         * 和awaitNanos基本一致，只是时间检测变成了和绝对时间相比较，而不是去判断超时时间的剩余量。
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            // 等待这么多纳秒
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            //  // 当前时间 + 等待时长 = 过期时间
            final long deadline = System.nanoTime() + nanosTimeout;
            // 用于返回 await 是否超时
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                // 时间到啦
                if (nanosTimeout <= 0L) {
                    // 这里因为要 break 取消等待了。取消等待的话一定要调用 transferAfterCancelledWait(node) 这个方法
                    // 如果这个方法返回 true，在这个方法内，将节点转移到阻塞队列成功
                    // 返回 false 的话，说明 signal 已经发生，signal 方法将节点转移了。也就是说没有超时嘛
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                // spinForTimeoutThreshold 的值是 1000 纳秒，也就是 1 毫秒
                // 也就是说，如果不到 1 毫秒了，那就不要选择 parkNanos 了，自旋的性能反而更好
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                // 得到剩余时间
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         *
         * 判断当前条件是否由给定的同步器(AQS)创建。
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         *
         * 判断当前条件队列中是否存在等待的线程。
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively()) //前提必须是当前线程独占的持有控制
                throw new IllegalMonitorStateException();
            //遍历条件等待队列，查找等待线程(节点)
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         *
         *  获取当前条件等待队列中等待线程的(估计)数量。
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         *
         *  获取当前条件等待队列中的等待线程。
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
