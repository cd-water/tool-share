package com.cdwater.toolshare.shared.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把外部副作用（Redis、消息、向量索引）推迟到数据库事务提交之后执行。
 * <p>事务回滚时回调不会执行；没有事务时立即执行，保证定时任务与只读路径行为不变。
 * <p>单实例部署假设见 ADR-0004。
 */
@Slf4j
@Component
public class AfterCommit {

    public void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    execute(action);
                }
            });
        } else {
            execute(action);
        }
    }

    /** 提交已经完成，副作用失败只能记录日志，不能再回滚业务数据。 */
    private void execute(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("事务提交后副作用执行失败", e);
        }
    }
}
