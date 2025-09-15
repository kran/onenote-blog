package onelog.utils;

import cn.hutool.core.date.DateUtil;
import cn.hutool.cron.task.Task;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
abstract public class AbstractTask implements Task {

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void execute() {
        var className = this.getClass().getSimpleName();

        if (running.get()) {
            log.info("Task is running, ignore: {}, {}", className, this);
            return;
        }

        var startTs = DateUtil.current();
        try {
            //log.info("Task start: {}", className);
            running.set(true);
            run();
        }
        catch (Throwable e) {
            log.warn("Task [{}] error: {}, {}",  className, e.getMessage(), this, e);
        }
        finally {
            //log.info("Task done: {}", className);
            running.set(false);
        }
    }

    public abstract void run();
}