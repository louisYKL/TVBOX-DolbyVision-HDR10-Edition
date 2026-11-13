package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.subtitle.runtime.TaskExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class UIRenderTaskTest {
    private final List<Runnable> mainQueue = new ArrayList<>();
    private int callbackCount;

    @Before
    public void setUp() {
        ((AppTaskExecutor) AppTaskExecutor.getInstance()).setDelegate(new TaskExecutor() {
            @Override
            public void executeOnDeskIO(Runnable task) {
                task.run();
            }

            @Override
            public void postToMainThread(Runnable task) {
                mainQueue.add(task);
            }

            @Override
            public boolean isMainThread() {
                return false;
            }
        });
    }

    @After
    public void tearDown() {
        ((AppTaskExecutor) AppTaskExecutor.getInstance()).setDelegate(null);
    }

    @Test
    public void cancelDropsQueuedCue() {
        UIRenderTask task = new UIRenderTask(subtitle -> callbackCount++);
        task.execute(new Subtitle());
        assertEquals(1, mainQueue.size());
        task.cancel();
        mainQueue.remove(0).run();
        assertEquals(0, callbackCount);
    }

    @Test
    public void generationGuardDropsStaleCue() {
        UIRenderTask task = new UIRenderTask(subtitle -> callbackCount++);
        task.execute(new Subtitle(), () -> false);
        mainQueue.remove(0).run();
        assertEquals(0, callbackCount);
    }
}
