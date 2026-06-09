package com.aicall.config;

import com.aicall.service.DialogScriptPackImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 启动时自动导入银行贷款话术包（库为空且开关开启） */
@Component
@RequiredArgsConstructor
public class DialogScriptPackStartup {

    private final DialogScriptPackImportService dialogScriptPackImportService;

    @EventListener(ContextRefreshedEvent.class)
    public void onReady() {
        dialogScriptPackImportService.importOnStartupIfEmpty();
    }
}
