package com.nanobeaconnetwork.consumer;

import android.app.Application;

import com.nanobeaconnetwork.NbnClient;
import com.nanobeaconnetwork.NbnConfig;
import com.nanobeaconnetwork.model.ReportStats;

import kotlinx.coroutines.flow.StateFlow;

public final class ConsumerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NbnConfig config = new NbnConfig.Builder()
                .scanSource(NbnConfig.ScanSource.HOST_SCAN)
                .build();
        NbnClient.INSTANCE.init(this, config);

        StateFlow<ReportStats> reportStats = NbnClient.INSTANCE.getReportStats();
        ReportStats currentStats = reportStats.getValue();
        currentStats.getPendingCount();
    }
}
