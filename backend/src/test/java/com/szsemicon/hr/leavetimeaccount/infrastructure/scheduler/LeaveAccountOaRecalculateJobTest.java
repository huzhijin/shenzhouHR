package com.szsemicon.hr.leavetimeaccount.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class LeaveAccountOaRecalculateJobTest {

    @Test
    void runsAtOneAmShanghaiByDefault() throws Exception {
        Method method = LeaveAccountOaRecalculateJob.class.getDeclaredMethod("runDaily");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron())
                .isEqualTo("${shenzhouhr.leave-account.oa-recalculate-cron:0 0 1 * * ?}");
        assertThat(scheduled.zone())
                .isEqualTo("${shenzhouhr.oa.auto-sync-zone:Asia/Shanghai}");
    }
}
