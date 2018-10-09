import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Test {
    public static void main(String[] agrs){
        ThreadPoolOfLijo lijo =new ThreadPoolOfLijo(10,50,200,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(20));
        for(int i=0;i<20;i++){
            MyTask task = new MyTask(i);
            lijo.execute(task);
            System.out.println("线程池中的线程数："+lijo.getPoolSize()+",阻塞队列中的任务：" + lijo.getWorkQueue().size() + ",已经执行完毕的任务：" + lijo.getCompletedTaskCount());
        }

        lijo.shutdown();
    }
}

class MyTask implements Runnable{
    int num;

    public MyTask(int i){
        this.num=i;
    }

    public void run(){
        try{
            System.out.println("正在执行task："+num);
            Thread.sleep(4000);
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            System.out.println("task :"+num+"，执行完毕");
        }
    }
}
