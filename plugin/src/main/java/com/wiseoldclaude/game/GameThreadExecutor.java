package com.wiseoldclaude.game;

/** Runs a task on the RuneLite client game thread. Prod impl: clientThread::invoke. */
@FunctionalInterface
public interface GameThreadExecutor
{
    void run(Runnable r);
}
