
import javafx.concurrent.Worker;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadPoolOfLijo extends AbstractExecutorService{

    private final AtomicInteger stateAndCount = new AtomicInteger(stateAndCountOf(RUNNING,0));
    //高3位存储线程池runState，低29位存储threadCount
    private final static int COUNT_BITS = Integer.SIZE-3;
    //获得存储线程数量的位数
    private final static int CAPACITY = (1 << COUNT_BITS)-1;

    //线程池的5种状态
    private static final int RUNNING = -1 << COUNT_BITS;
    //接收新任务且处理队列中的任务
    private static final int SHUTDOWN = 0 << COUNT_BITS;
    //尝试停止正在执行的任务，不接收新任务但是处理队列中的任务
    private static final int STOP = 1 << COUNT_BITS;
    //尝试中断正在执行的任务，不接受新任务且不处理队列中的任务
    private static final int TIDYING = 2 << COUNT_BITS;
    //所有任务已经终止，所有线程状态都更改为TIDYING，并将运行terminated()
    private static final int TERMINATED = 3 << COUNT_BITS;
    //terminated()执行完毕

    //装箱拆箱方法
    private static int runStateOf(int runstate) {return runstate & ~CAPACITY;}
    //获取高3位线程池状态
    private static int workerCountOf(int workercount) {return workercount & CAPACITY;}
    //获取低29位线程数量
    private static int stateAndCountOf(int rs,int wc) {return rs | wc;}
    //返回32融合变量，作为stateAngCount的值

    //判断状态的5种方法
    private static boolean isRunning(int state) {return state < SHUTDOWN;}
    public  boolean isShutdown() {return !isRunning(stateAndCount.get());}
    public boolean isTerminating() {
        int state = stateAndCount.get();
        return !isRunning(state) && runStateLessThan(state,TERMINATED);
    }
    public boolean isTerminated() {
        return runStateAtLeast(stateAndCount.get(),TERMINATED);
    }

    private static boolean runStateLessThan(int first ,int second){return first<second;}
    private static boolean runStateAtLeast(int first ,int second){return first>=second;}

    private boolean compareAndIncrementWorkerCount(int expect){
        return stateAndCount.compareAndSet(expect,expect+1);
    }
    private boolean compareAndDecrementWorkerCount(int expect){
        return stateAndCount.compareAndSet(expect,expect-1);
    }
    private void decrementWorkerCount(){
        do {} while(!compareAndDecrementWorkerCount(stateAndCount.get()));
    }

    private static final boolean ONLY_ONE=true;

    private final BlockingQueue<Runnable> workQueue;
    //任务阻塞队列
    private final ReentrantLock mainLock=new ReentrantLock();
    //重入锁
    private final HashSet<Worker> workers=new HashSet<Worker>();
    //存储Worker实例
    private final Condition termination = mainLock.newCondition();
    //等待状态用来支持等待终止
    private volatile RejectedExecutionHandler handler;

    private int largestPoolSize;
    //记录线程池达到的最大大小
    private long completedTaskCount;
    //记录已经执行完毕的Task数量
    private volatile ThreadFactory threadFactory;
    //线程工厂在addWorker里创建线程
    private volatile long keepAliveTime;
    //空闲线程的存活时间
    private volatile boolean allowCoreThreadTimeOut;
    //是否允许核心线程在空闲时存活
    private volatile int corePoolSize;
    //核心线程数
    private volatile int maxPoolSize;
    //最大线程数
    private static final RejectedExecutionHandler defaultHandler=new AbortPolicy();

    private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");

    private final AccessControlContext acc;

    private final class Worker extends AbstractQueuedSynchronizer implements Runnable{

        //private static final long serialVersionUID=6138294804551838834L;
        //看不懂什么操作
        final Thread thread;
        //这个Worker类工作的线程
        Runnable firstTask;
        volatile long completedTask;

        Worker(Runnable firstTask){
            setState(-1);
            //直到runWorker之前，不允许中断
            this.firstTask=firstTask;
            this.thread=getThreadFactory().newThread(this);
            //创建一个包裹自身的线程
        }

        @Override
        public void run() {
            runWorker(this);
        }

        protected boolean isHeldExclusively(){
            return getState()!=0;
        }
        //0代表无锁，1代表有锁

        protected boolean tryAcquire(int unused){
            if(compareAndSetState(0,1)){
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused){
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()           {acquire(1);}
        public boolean tryLock()     {return tryAcquire(1);}
        public void unlock()         {release(1);}
        public boolean isLocked()    {return isHeldExclusively();}

        void interruptIfStarted(){
            Thread temp;
            if(getState() >= 0 && (temp=thread)!=null && !temp.isInterrupted()){
                try{
                    temp.interrupt();
                }catch (SecurityException e){ }
            }
        }
    }

    public void execute(Runnable  command){
        if(command == null){
            throw new NullPointerException();
            //如果任务为空，抛出指针异常
        }

        int state = stateAndCount.get();
        if(workerCountOf(state) < corePoolSize){
            if(addWorker(command,true))
                return;
            //如果线程池中线程数小于核心线程数，且addWorker()成功，返回
            state = stateAndCount.get();
        }

        if(isRunning(state) && workQueue.offer(command)){
            System.out.println("offer成功");
            int recheck = stateAndCount.get();
            //如果任务能够成功排队，再次获得运行状态，防止进入方法快之后线程池SHUTDOWN
            if(!isRunning(recheck) && workQueue.remove(command)){
                //如果线程池不处于运行状态，则从阻塞队列中移除任务，尝试终止线程池，且回绝任务
                tryTerminate();
                reject(command);
            }
            else if (workerCountOf(recheck) == 0)
                //如果任务排队成功，且线程池中的线程数为0，则添加null线程
                addWorker(null,false);
        }
        else if(!addWorker(command,false))
            //如果线程池不处于RUNNING且或者任务排队不成功回绝任务
            reject(command);
    }


    private void advanceRunState(int target){
        for(;;){
            int runstate=stateAndCount.get();
            if(runStateAtLeast(runstate,target)||
               stateAndCount.compareAndSet(runstate,stateAndCountOf(target,workerCountOf(runstate))))
                break;
        }
    }

    public ThreadFactory getThreadFactory(){
        return threadFactory;
    }
    //返回线程工厂实例

    final void tryTerminate(){
        for(;;){
            int state=stateAndCount.get();
            if(isRunning(state) || runStateAtLeast(state,TIDYING) || (isShutdown() && !workQueue.isEmpty()))
                //判断状态
                return;
            if(workerCountOf(state)!=0){
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainlock=this.mainLock;
            mainlock.lock();
            try{
                if(stateAndCount.compareAndSet(state,stateAndCountOf(TIDYING,0))){
                    try{
                        terminated();
                    }finally {
                        stateAndCount.set(stateAndCountOf(TERMINATED,0));
                        termination.signalAll();
                    }
                }
            }finally {
                mainlock.unlock();
            }

        }
    }

    private void interruptIdleWorkers(boolean onlyOne){
        final ReentrantLock mainlock=this.mainLock;
        mainlock.lock();
        try{
            for(Worker w:workers){
                Thread thread=w.thread;
                if(!thread.isInterrupted() && w.tryLock()){
                    try{
                        thread.interrupt();
                    }catch (SecurityException e){
                    }finally {
                        w.unlock();
                    }
                }
                if(onlyOne)
                    break;
            }
        }finally {
            mainlock.unlock();
        }
    }

    private void interruptIdleWorkers() {interruptIdleWorkers(false);}

    private void interruptWorkers(){
        final ReentrantLock mainlock=this.mainLock;
        mainlock.lock();
        try{
            for(Worker worker:workers)
                worker.interruptIfStarted();
        }finally {
            mainlock.unlock();
        }
    }

    final void reject(Runnable command) {
        ThreadPoolExecutor lijo=null;
        handler.rejectedExecution(command,lijo);
    }

    void onShutdown() {}

    final boolean isRunningOrShutdown(boolean shutdownOk){
        int state=runStateOf(stateAndCount.get());
        return state == RUNNING || (state == SHUTDOWN && shutdownOk);
    }

    private List<Runnable> drainQueue(){
        BlockingQueue<Runnable> queue=workQueue;
        ArrayList<Runnable> taskList =new ArrayList<Runnable>();
        queue.drainTo(taskList);
        if(!queue.isEmpty()){
            for(Runnable runnable:queue.toArray(new Runnable[0])){
                if(queue.remove(runnable))
                    taskList.add(runnable);
            }
        }
        return taskList;
    }

    private boolean addWorker(Runnable task,boolean core){
        retry:
        for(;;){
            int c=stateAndCount.get();
            int state=runStateOf(c);
            /*
                1：如果线程池至少已经达到了SHUTDOWN状态
                2：如果一下3个条件有个一个为false，那就没有添加worker的必要了
                    A：rs==SHUTDOWN：隐含超过SHUTDOWN的所有状态，即线程池已经终止
                    B：task==null：线程池已经处于终止状态，但是task仍不为空，则代表还有有新任务要接受，自然拒绝
                    C：workQueue.isEmpty()：线程池添加task为空的worker是为了让线程池中至少保留一条线程，来维持线程池并从阻塞队列中获取任务来执行，如果workQueue已经为空，自然没有必要添加worker了
             */
            if(state>=SHUTDOWN && !(state == SHUTDOWN && task!=null && !workQueue.isEmpty()))
                return false;
            for(;;){
                int count=workerCountOf(c);
                //如果worker数量已经超过CAPACITY或者worker数量大于corePoolSize或者maxPoolSize（前者是阻塞队列未满，后者是阻塞队列已满），即已经超过了给定的边界
                if(count>=CAPACITY||count>=(core?corePoolSize:maxPoolSize))
                    return false;
                if(compareAndIncrementWorkerCount(c))
                    break retry;
                c=stateAndCount.get();
                //worker+1失败，再次读取线程池状态
                if(runStateOf(c)!=state)
                    //如果不等于之前的状态，则线程池被改变了，重新判断
                    continue retry;
            }
        }

        boolean workerStarted=false;
        boolean workerAdded=false;
        Worker worker=null;

        try{
            worker=new Worker(task);
            final Thread thread=worker.thread;
            //使用worker自身的Runnable，调用线程工厂创建一个线程，worker.thread
            if (thread!=null){
                final ReentrantLock mainlock=this.mainLock;
                mainlock.lock();
                try{
                    int state=runStateOf(stateAndCount.get());
                    //获取到锁之后从新检查
                    if(state<SHUTDOWN||state==SHUTDOWN&&task==null){
                        /*
                            两种情况
                                A：线程池处于RUNNING，正常接收新任务
                                B：线程池处于SHUTDWON，且task为空，则可能是阻塞队列中仍有没有执行的任务在等待，所以创建一个没有初始化任务的worker来执行阻塞队列中剩余的任务
                         */
                        if(thread.isAlive())
                            throw new IllegalThreadStateException();
                            //抛出非法线程状态异常
                        workers.add(worker);
                        //addWorker成功~
                        workerAdded=true;
                        if(workers.size()>largestPoolSize)
                            largestPoolSize=workers.size();
                            //更新largestPoolSize数据
                    }
                }finally {
                    mainlock.unlock();
                }
                if(workerAdded){
                    thread.start();
                    //线程开始
                    workerStarted=true;
                }
            }
        }finally {
            if(!workerStarted)
                //worker添加失败
                addWorkerFailed(worker);
        }

        return workerStarted;
    }

    private void addWorkerFailed(Worker worker){
        final ReentrantLock mainlock=this.mainLock;
        mainlock.lock();
        try{
            if(worker!=null){
                workers.remove(worker);
                //从workersHashSet中移除对应worker
                decrementWorkerCount();
                //Worker数量-1
                tryTerminate();
            }
        }finally {
            mainlock.unlock();
        }
    }

    private void processWorkerExit(Worker worker,boolean abrupt){
        if(abrupt)
            decrementWorkerCount();
        //如果是突然结束的，那么在getTask里WorkerCount并没有-1
        final ReentrantLock mainlock=this.mainLock;
        mainlock.lock();
        try{
            completedTaskCount+=worker.completedTask;
            //将此worker完成的任务数加到线程池的任务完成数中
            workers.remove(worker);
            //移除
        }finally {
            mainlock.unlock();
        }

        tryTerminate();

        int state =stateAndCount.get();
        if (runStateLessThan(state,STOP)){
            //如果线程池小于STOP状态，尝试再添加一个worker
            if(!abrupt){
                int min=allowCoreThreadTimeOut?0:corePoolSize;
                if(min == 0 && !workQueue.isEmpty()){
                    //如果线程池为空，且阻塞队列不为空，则增加一个Worker
                    addWorker(null,false);
                    min=1;
                }
                if(workerCountOf(state)>=min)
                    //如果线程池中的Worker数量，大于需要维护的数量，则return
                    return;
            }
        }

    }

    private Runnable getTask(){
        boolean timeOut=false;
        //最后一次的poll操作是否超时，默认false

        for(;;){
            int sateandcount=stateAndCount.get();
            int state=runStateOf(sateandcount);

            if(state >= SHUTDOWN && (state >=STOP || workQueue.isEmpty())){
                /*
                有两种情况会减少worker数量，并且返回null
                    A：线程池状态为SHUTDOWN，且阻塞队列为空，这也反映了当线程池处于SHUTDOWN状态下时，是要处理阻塞队列中的任务的
                    B：线程池状态为STOP，此时不用考虑阻塞队列的情况
                 */

                decrementWorkerCount();
                //减少worker数量
                return null;
            }

            int count=workerCountOf(sateandcount);
            //这里再获得worker数量，可以保证此时worker数量是正确的，因为要考虑worker在上一代码块减少的情况

            boolean timed=allowCoreThreadTimeOut || count>corePoolSize;
            //allowCoreThreadTimeOut默认false

            if((count > maxPoolSize || (timed && timeOut)) && (count > 1 || workQueue.isEmpty())){
                if(compareAndDecrementWorkerCount(sateandcount))
                    return null;
                continue;
            }
            //看不懂

            try{
                Runnable runnable =timed ? workQueue.poll(keepAliveTime,TimeUnit.NANOSECONDS) : workQueue.take();
                /*
                  根据timed的值有两种情况
                    A:timed==true，说明此时线程池的worker数量大于corePoolSize，pool()会挂起keepAliceTime时长，interrupt()不会抛出异常，但是会有中断反应
                    B：timed==false，说明此时线程池的worker数量小于corePoolSize，take()挂起，interrupt()不会抛出异常，但是会有中断反应
                 */

                if(runnable != null)
                    //获取到任就返回
                    return runnable;
                timeOut=true;
            }catch ( InterruptedException e){
                timeOut=false;
            }
        }
    }
    final void runWorker(Worker worker){
        Thread thread=Thread.currentThread();
        Runnable task=worker.firstTask;
        worker.firstTask=null;
        worker.unlock();
        //解锁，以便允许中断
        //new Worker()是state==-1，此处是调用Worker类的tryRelease()方法，将state置为0,因为interruptIfStarted()中只有state>=0才允许调用中断
        boolean abryput=true;
        //突然中断，默认为true

        try{
            while(task != null ||(task = getTask()) != null){
                worker.lock();
                //确保SHUTDOWN时，正在执行的worker不会被中断

                if((runStateAtLeast(stateAndCount.get(),STOP) ||
                    (Thread.interrupted() &&
                    runStateAtLeast(stateAndCount.get(),STOP))) &&
                    !thread.isInterrupted()){
                    thread.interrupt();
                }
                /*
                 * clearInterruptsForTaskRun操作
                 * 确保只有在线程STOP时，才会被设置中断标示，否则清除中断标示
                 * A:如果线程池状态>=STOP，且当前线程没有设置中断状态，thread.interrupt()
                 * B:如果一开始判断线程池状态<stop，但Thread.interrupted()为true，即线程已经被中断，又清除了中断标示，再次判断线程池状态是否>=stop
                 *   是:再次设置中断标示，wt.interrupt()
                 *   否:不做操作，清除中断标示后进行后续步骤
                 */

                try{
                    beforeExecute(thread,task);

                    Throwable thrown=null;

                    try{
                        task.run();
                    }catch (RuntimeException e){
                        thrown=e;
                        throw e;
                    }catch (Error e){
                        thrown=e;
                        throw e;
                    }catch (Throwable e){
                        thrown=e;
                        throw e;
                    }
                }finally {
                    task=null;
                    //task置为空
                    worker.completedTask++;
                    //此worker完成任务++
                    worker.unlock();
                    //解锁，可以中断了
                }
            }
            abryput=false;
            //标志位置为false：并非突然结束，作为参数传入worker退出函数
        }finally {
            processWorkerExit(worker,abryput);
            //处理worker的退出
        }
    }

    public ThreadPoolOfLijo(int corePoolSize,
                            int maxPoolSize,
                            long keepAliveTime,
                            TimeUnit unit,
                            BlockingQueue<Runnable> workQueue){
        this(corePoolSize,maxPoolSize,keepAliveTime,unit,workQueue,
                Executors.defaultThreadFactory(),defaultHandler);
    }

    public ThreadPoolOfLijo(int corePoolSize,
                            int maxPoolSize,
                            long keepAliveTime,
                            TimeUnit unit,
                            BlockingQueue<Runnable> workQueue,
                            ThreadFactory threadFactory){
        this(corePoolSize,maxPoolSize,keepAliveTime,unit,workQueue,threadFactory,defaultHandler);
    }

    public ThreadPoolOfLijo(int corePoolSize,
                            int maxPoolSize,
                            long keepAliveTime,
                            TimeUnit unit,
                            BlockingQueue<Runnable> workQueue,
                            RejectedExecutionHandler handler){
        this(corePoolSize,maxPoolSize,keepAliveTime,unit,workQueue,Executors.defaultThreadFactory(),handler);
    }

    public ThreadPoolOfLijo(int corePoolSize,
                            int maxPoolSize,
                            long keepAliveTime,
                            TimeUnit unit,
                            BlockingQueue<Runnable> workQueue,
                            ThreadFactory threadFactory,
                            RejectedExecutionHandler handler){
        if(corePoolSize<0 ||
            maxPoolSize<=0 ||
            maxPoolSize<corePoolSize ||
            keepAliveTime<0)

            throw new IllegalArgumentException();

        if (workQueue==null ||
            threadFactory==null ||
            handler==null)

            throw new NullPointerException();

        this.acc = System.getSecurityManager() == null ? null:AccessController.getContext();
        this.corePoolSize=corePoolSize;
        this.maxPoolSize=maxPoolSize;
        this.workQueue=workQueue;
        this.keepAliveTime=unit.toNanos(keepAliveTime);
        this.threadFactory=threadFactory;
        this.handler=handler;
    }

    protected void terminated(){}

    private void checkShutdownAccess(){
        SecurityManager manager = System.getSecurityManager();
        if(manager != null){
            manager.checkPermission(shutdownPerm);
            //应该是一种系统性的安全性检查，检查有无“modify thread”的权限
            final ReentrantLock mainlock = this.mainLock;
            mainlock.lock();
            try{
                for(Worker worker :workers)
                    manager.checkAccess(worker.thread);
            }finally {
                mainlock.unlock();
            }
        }
    }

    @Override
    public void shutdown() {
        final ReentrantLock mainlock=this.mainLock;
        mainlock.lock();
        try{
            checkShutdownAccess();
            //方法内部应该是一种系统性的安全性检查，检查有无“modify thread”的权限
            advanceRunState(SHUTDOWN);
            //这个方法看不懂
            interruptIdleWorkers();
            //foreach，逐个中断
            onShutdown();
            //ScheduledThreadPool挂钩
        }finally {
            mainlock.unlock();
        }
        tryTerminate();
        //尝试把线程池的状态置为TERMINATED
    }

    public List<Runnable> shutdownNow(){
        List<Runnable> tasklist;
        final ReentrantLock mainlock = this.mainLock;
        mainlock.lock();
        try{
            checkShutdownAccess();
            advanceRunState(STOP);
            interruptWorkers();
            tasklist=drainQueue();
        } finally {
            mainLock.unlock();
        }

        tryTerminate();
        return tasklist;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.mainLock;
        lock.lock();

        try{
            for(;;){
                if(runStateAtLeast(stateAndCount.get(),TERMINATED))
                    return true;
                if(nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        }finally {
            mainLock.unlock();
        }
    }

    protected void finalize(){
        SecurityManager manager = System.getSecurityManager();
        if(manager == null || acc == null)
            shutdown();
        else{
            PrivilegedAction<Void> pa = () -> {shutdown(); return null;};
            AccessController.doPrivileged(pa,acc);
        }
    }

    public void setThreadFactory(ThreadFactory factory){
        if(factory == null)
            throw new NullPointerException();
        this.threadFactory=factory;
    }

    public void setRejectedExecutionHandler(RejectedExecutionHandler handler){
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    public RejectedExecutionHandler getRejectedExecutionHandler(){
        return this.handler;
    }

    public void setCorePoolSize(int corePoolSize){
        if(corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        //新核心线程池大小与旧核心线程池大小之差
        this.corePoolSize=corePoolSize;
        //设置新核心线程池大小
        if(workerCountOf(stateAndCount.get()) > corePoolSize)
            interruptIdleWorkers();
        //如果线程池中运行的线程数大于新核心线程池大小（相当于，新核心线程池大小 < 就核心线程池大小），中断线程
        else if(delta > 0){
            int min = Math.min(delta,workQueue.size());
            //取最小值
            while(min-- > 0 && addWorker(null,false)){
                /*
                当新设置的核心线程池大小（简称new）大于之前的核心线程池大小时（简称old），我们自然需要往线程池里添加新线程，此时有两种情况
                    A：delta < 阻塞队列的大小：以delata为标杆，尽可能地添加线程，直到把核心线程池塞满
                    B：阻塞队列的大小 < delta：以阻塞队列大小为标杆，添加相应个数的线程
                 */
                if(workQueue.isEmpty())
                    break;
                //如果在添加阶段，阻塞队列为空，自然没有添加线程的必要了
            }
        }

    }

    public void setMaxPoolSize (int maxPoolSize){
        if(maxPoolSize < 0 ||maxPoolSize < corePoolSize)
            throw new IllegalArgumentException("最大线程池数至少大于等于核心线程池数");
        this.maxPoolSize = maxPoolSize;
        if(workerCountOf(stateAndCount.get()) > maxPoolSize)
            //如果线程池中的线程数大于最大线程池数，则中断，显然这是调小
            interruptIdleWorkers();
    }

    public void setKeepAliveTime(long time,TimeUnit unit) {
        //与前两个方法块差不多
        if(time < 0)
            throw new IllegalArgumentException("时间不能小于0");
        if(time == 0 || allowCoreThreadTimeOut)
            throw new IllegalArgumentException("时间为0，与允许超时，两者冲突");
        long keepalivetime = unit.toNanos(time);
        long delta = keepalivetime - this.keepAliveTime;
        this.keepAliveTime = keepalivetime;
        if(delta < 0)
            interruptIdleWorkers();
    }

    public boolean remove(Runnable task) {
        tryTerminate();
        return workQueue.remove(task);
    }

    public int getCorePoolSize() {return corePoolSize;}

    public int getMaxPoolSize() {return maxPoolSize;}

    public long getKeepAliveTime() {return keepAliveTime;}

    public BlockingQueue<Runnable> getWorkQueue() {return workQueue;}

    public int getPoolSize(){
        final ReentrantLock mainlock = this.mainLock;
        mainlock.lock();
        try{
            return runStateAtLeast(stateAndCount.get(),TIDYING) ?
                    0 : workers.size();
            //如果线程池处于TIDYING以上状态，自然线程池大小为0，如果不是，返回worker工作集大小
        }finally {
            mainlock.unlock();
        }
    }

    public int getActiveCount(){
        final ReentrantLock mainlock = this.mainLock;
        mainlock.lock();
        try{
            int count = 0;
            for(Worker worker :workers){
                //遍历worker工作集
                if(worker.isLocked())
                    count++;
                //如果某worker暂未释放锁，自然此worker是处于活动状态的
            }
            return count;
        } finally {
            mainlock.unlock();
        }
    }

    public int getLargestPoolSize(){
        final ReentrantLock mainlock = this.mainLock;
        mainlock.lock();
        //即使未对参数就行操作，仅仅是取值，重入锁也是必不可少的，因为我们要确定，在这一刻我们取到的值没有在别处被改变
        try{
            return largestPoolSize;
        }finally {
            mainlock.unlock();
        }
    }

    public long getTaskCount(){
        final ReentrantLock mainlock =this.mainLock;
        mainlock.lock();
        try{
            long count = completedTaskCount;
            //completedTaskCount是所有worker已经完成的任务数，但是它在下面又加了一次
            for(Worker worker : workers){
                count += worker.completedTask;
                //没有任务执行的worker会被从workers工作集中remove掉，剩下的worker都是还有任务要执行的
                //但是在工作中worker执行的task是实时加到completedTaskCount中的，所以为什么还要加一遍
                //令人困惑
                if(worker.isLocked())
                    count++;
                //这里也是
            }

            return count+workQueue.size();
            //这里更是
        } finally {
            mainlock.unlock();
        }
        //值得指出的是，这个方法并没有多大的实际意义，因为执行的task数，无时不刻不在改变，我们能得到只能是一个近似值
        //只能供分析用
    }

    public long getCompletedTaskCount(){
        //跟上个方法差不多
        final ReentrantLock mainlock = this.mainLock;
        mainlock.lock();

        try{
            long count = completedTaskCount;
            for(Worker worker : workers)
                count += worker.completedTask;
            return count;
        } finally {
            mainlock.unlock();
        }
    }

    public String toString(){
        //跟前面一样，这个是总览
        long nccmpleted;
        int nworkers,nactive;
        final ReentrantLock mainlock = this.mainLock;
        mainlock.lock();

        try{
            nccmpleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for(Worker worker:workers){
                nccmpleted += worker.completedTask;
                if(worker.isLocked())
                    nactive++;
            }
        } finally {
            mainlock.unlock();
        }

        int state = stateAndCount.get();

        String rs = (runStateLessThan(state,SHUTDOWN) ?
                "Running" :(runStateAtLeast(state,TERMINATED) ?
                    "Terminated" :"Shutting down"));
        return super.toString()+
                "[" + rs +
                ", pool size = " + nworkers +
                ", active threads = " + nactive +
                ",queued tasks = " + workQueue.size() +
                ",completed tasks = " + nccmpleted +
                "]";
    }

    public void purge(){
        final BlockingQueue<Runnable> queue = workQueue;

        try{
            Runnable runnable;
            Iterator<Runnable> iterator = queue.iterator();
            //迭代器捉个迭代
            while(iterator.hasNext()){
                runnable = iterator.next();
                if(runnable instanceof Future<?> && ((Future<?>)runnable).isCancelled())
                    iterator.remove();
                    //移除
            }
        }catch (ConcurrentModificationException e){
            /*
            如果迭代器在迭代期间遇到干扰或者发生异常，贼用for each逐个遍历
            这样应该会慢一点
             */
            for(Object object : queue.toArray()){
                if(object instanceof Future<?> && ((Future<?>)object).isCancelled())
                    queue.remove(object);
            }
        }

        tryTerminate();
        //防止线程池状态突然为SHUTDOWN，或者阻塞队列为空
    }

    public boolean prestartCoreThread(){
        return workerCountOf(stateAndCount.get()) <corePoolSize && addWorker(null,false);
    }

    void ensurePrestart(){
        int count = workerCountOf(stateAndCount.get());
        if(count < corePoolSize)
            addWorker(null,true);
        else if(count == 0)
            addWorker(null,false);
    }

    public boolean prestartAllCoreThreads(){
        int n=0;
        while(addWorker(null,true))
            n++;
        return n == corePoolSize;
    }

    public boolean addlsCoreThreadTimeOut() {return allowCoreThreadTimeOut;}

    public void allowCoreThreadTimeOut(boolean value){
        if(value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if(value != allowCoreThreadTimeOut){
            allowCoreThreadTimeOut = value;
            if(value)
                interruptIdleWorkers();
        }
    }
    protected void beforeExecute(Thread thread,Runnable runnable) {}

    protected void afterExecute(Thread thread,Runnable runnable) {}

    //几种拒绝策略

    public static class AbortPolicy implements RejectedExecutionHandler{

        public AbortPolicy() {}

        public void rejectedExecution(Runnable runnable,ThreadPoolExecutor lijo){
            throw new RejectedExecutionException("Task" + runnable.toString()+
                                                "rejected from "+
                                                lijo.toString());
        }


    }

    public static class CallerRunsPolicy implements RejectedExecutionHandler{
        public CallerRunsPolicy() {}

        public void rejectedExecution(Runnable runnable,ThreadPoolExecutor lijo){
            if(!lijo.isShutdown())
                runnable.run();
        }
    }

    public static class DiscardPolicy implements RejectedExecutionHandler{
        public DiscardPolicy() {}

        public void rejectedExecution(Runnable runnable,ThreadPoolExecutor lijo){ }
    }

    public static class DiscardOldestPolicy implements RejectedExecutionHandler{
        public DiscardOldestPolicy() {}

        public void rejectedExecution(Runnable runnable,ThreadPoolExecutor lijo){
            if(!lijo.isShutdown()){
                lijo.getQueue().poll();
                lijo.execute(runnable);
            }
        }
    }
}