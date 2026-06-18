package com.aicall.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TableCommentSchemaInitializerTest {

    @Autowired
    private TableCommentSchemaInitializer tableCommentSchemaInitializer;

    @Test
    void applyTableComments() {
        tableCommentSchemaInitializer.apply();
    }
}
