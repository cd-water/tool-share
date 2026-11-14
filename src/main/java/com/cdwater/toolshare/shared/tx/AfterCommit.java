package com.cdwater.toolshare.shared.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把外部副作用（Redis、消息、向量索引）推迟到数据库事务提交之后执行。
 * <p>事务回滚时回调不会执行；没有事务时立即执行，保证定时任务与只读路径行为不变。
 * <p>单实例部署假设见 ADR-0004。
 *
 * <p>典型用法：业务数据在同一事务内写入，提交成功后再刷新缓存或发送
 * 消息，避免“事务最终回滚但副作用已经发生”的不一致。回调内部异常
 * 只能记录日志，因为此时数据库已经提交，无法再回滚。</p>
 *
 * <p>没有活动事务时直接执行，是为了让后台任务、测试与只读路径不必
 * 额外开启事务，同时保持“调用后副作用已完成”的直觉语义。</p>
 *
 * <p>注意：afterCommit 回调仍在原请求线程中同步执行，不应放入耗时
 * 很长的任务；若需要异步，应在回调内再提交到线程池。</p>
 *
 * <p>多实例部署时，副作用只会在处理该请求的实例上执行一次，不会广播
 * 到其它实例；如需全局一致性，应改用消息队列。</p>
 */
@Slf4j
@Component
public class AfterCommit {

    /**
     * 注册或立即执行一个副作用。
     *
     * <p>存在事务同步时注册 afterCommit 回调；否则立即执行。回调只在
     * 提交成功后触发，回滚不会执行。</p>
     *
     * @param action 需要在提交后执行的副作用
     */
    public void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 当前线程有活动事务：挂到提交后回调，随事务结果决定是否执行。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    execute(action);
                }
            });
        } else {
            // 没有事务：无法延迟，立即执行以保持语义一致。
            execute(action);
        }
    }

    /** 提交已经完成，副作用失败只能记录日志，不能再回滚业务数据。 */
    private void execute(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // 此处吞掉异常是刻意的：主事务已提交，抛出只会制造假失败。
            log.error("事务提交后副作用执行失败", e);
        }
    }
}
