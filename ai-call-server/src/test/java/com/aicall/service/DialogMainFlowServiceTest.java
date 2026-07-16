package com.aicall.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DialogMainFlowServiceTest {

    @Mock
    private DialogScriptPackRegistry scriptRegistry;

    private DialogMainFlowService service;

    private static final List<String> ORDER = List.of(
            "01", "02", "03", "04", "05", "06", "07", "07A", "08", "09", "10", "11", "12", "13", "13A", "14");

    @BeforeEach
    void setUp() {
        service = new DialogMainFlowService(scriptRegistry);
        lenient().when(scriptRegistry.isMainFlowEnabled(anyInt())).thenReturn(true);
        lenient().when(scriptRegistry.mainFlowStepOrder(1)).thenReturn(ORDER);
        lenient().when(scriptRegistry.mainFlowScript(eq("07"), eq(1))).thenReturn("工资呢！每月大概是多少呢！");
        lenient().when(scriptRegistry.mainFlowScript(eq("05"), eq(1))).thenReturn("那您社保当前连续缴纳多久了？");
        lenient().when(scriptRegistry.mainFlowScript(eq("08"), eq(1))).thenReturn("您名下的公司有没有开票或者交税呢");
        lenient().when(scriptRegistry.mainFlowScript(eq("12"), eq(1))).thenReturn("嗯，那您名下有车吗？");
        lenient().when(scriptRegistry.mainFlowScript(eq("13"), eq(1))).thenReturn("那再问一下啊，那您名下有房吗？");
        lenient().when(scriptRegistry.mainFlowScript(eq("13A"), eq(1))).thenReturn("好的，那请问您的这套房子是全款的呢，还是还在按揭中？");
        lenient().when(scriptRegistry.mainFlowScript(eq("07A"), eq(1))).thenReturn("好的，那我再了解一下，您目前的征信情况怎么样呢？");
        lenient().when(scriptRegistry.mainFlowScript(eq("14"), eq(1))).thenReturn("嗯，人寿保险购买过吗？");
        lenient().when(scriptRegistry.mainFlowScript(eq("04"), eq(1))).thenReturn("您社保和公积金都在正常缴纳吗？");
        lenient().when(scriptRegistry.mainFlowScript(eq("03"), eq(1))).thenReturn(
                "那我这边给您匹配下产品，有几个问题需要了解一下，请问您是上班还是做生意呢？");
        lenient().when(scriptRegistry.mainFlowScript(eq("27"), eq(1))).thenReturn(
                "啊，您别这么快拒绝嘛，现在这个贷款利息真的很低，而且呢您可以留着备用嘛！那我这边加一下微信您可以吗？");
        service.initCall(100, 1);
    }

    @Test
    void asrCorrection_replaysCurrentStep() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "说错了", "上班还是做生意");
        assertEquals("那我这边给您匹配下产品，有几个问题需要了解一下，请问您是上班还是做生意呢？", line);
        assertEquals("03", service.currentStep(100));
    }

    @Test
    void businessUser_skipsToStep08() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "做生意", "上班还是做生意");
        assertEquals("您名下的公司有没有开票或者交税呢", line);
        assertEquals("08", service.currentStep(100));
    }

    @Test
    void selfEmployed_skipsToStep08() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "自己干活的", "上班还是做生意");
        assertEquals("您名下的公司有没有开票或者交税呢", line);
        assertEquals("08", service.currentStep(100));
    }

    @Test
    void deniesSocialInsurance_skipsToAssets() {
        service.restoreStep(100, "04");
        String line = service.nextMainLineAfterUser(100, "你讲那些我都没有", "社保公积金都在正常缴纳吗");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void deliveryRider_skipsToAssets() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "跑外卖的", "上班还是做生意");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void noThose_skipsSocialToAssets() {
        service.restoreStep(100, "04");
        String line = service.nextMainLineAfterUser(100, "没有那些", "社保公积金都在正常缴纳吗");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void propertyProductInquiry_doesNotAdvanceMainFlow() {
        service.restoreStep(100, "05");
        String line = service.nextMainLineAfterUser(100, "我名下有房子可以办吗", "社保连续缴纳多久");
        assertEquals(null, line);
        assertEquals("05", service.currentStep(100));
    }

    @Test
    void homemaker_skipsToAssets() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "在家带小孩没上班", "上班还是做生意");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void homemakerNoSocial_skipsToAssets() {
        service.restoreStep(100, "03");
        service.nextMainLineAfterUser(100, "在家带小孩", "上班还是做生意");
        service.restoreStep(100, "04");
        String line = service.nextMainLineAfterUser(100, "那些都没有", "社保公积金都在正常缴纳吗");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void socialDenied_skipsRemainingSocialSteps() {
        service.restoreStep(100, "04");
        service.nextMainLineAfterUser(100, "你讲那些我都没有", "社保公积金都在正常缴纳吗");
        assertEquals("12", service.currentStep(100));
        service.restoreStep(100, "05");
        String line = service.nextMainLineAfterUser(100, "没有", "社保连续缴纳多久");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void wechatDeclineFromFaq_playsRetentionNotRepeatStep() {
        service.restoreStep(100, "15");
        String line = service.continueAfterWeChatDecline(100, 1,
                "你看这样可以吗？咋俩先加个微信，帮您先匹配一下您的可贷额度以及利息等，你看可以么！");
        assertEquals("啊，您别这么快拒绝嘛，现在这个贷款利息真的很低，而且呢您可以留着备用嘛！那我这边加一下微信您可以吗？", line);
        assertEquals("27", service.currentStep(100));
    }

    @Test
    void hasProperty_asksFullOrMortgage() {
        service.restoreStep(100, "13");
        String line = service.nextMainLineAfterUser(100, "我有房", "那您名下有房吗");
        assertEquals("好的，那请问您的这套房子是全款的呢，还是还在按揭中？", line);
        assertEquals("13A", service.currentStep(100));
    }

    @Test
    void employeeWithSocialFund_skipsStep04() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "上班有公积金", "上班还是做生意");
        assertEquals("那您社保当前连续缴纳多久了？", line);
        assertEquals("05", service.currentStep(100));
    }

    @Test
    void salaryThenCreditInquiry() {
        service.restoreStep(100, "07");
        String line = service.nextMainLineAfterUser(100, "一万", "工资呢");
        assertEquals("好的，那我再了解一下，您目前的征信情况怎么样呢？", line);
        assertEquals("07A", service.currentStep(100));
    }

    @Test
    void businessDenyInvoice_skipsToAssets() {
        service.restoreStep(100, "08");
        String line = service.nextMainLineAfterUser(100, "没有", "公司有没有开票或者交税");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void constructionWorker_skipsToAssets() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "工地干活的", "上班还是做生意");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void retired_skipsToAssets() {
        service.restoreStep(100, "03");
        String line = service.nextMainLineAfterUser(100, "已经退休了", "上班还是做生意");
        assertEquals("嗯，那您名下有车吗？", line);
        assertEquals("12", service.currentStep(100));
    }

    @Test
    void abuseBlocksMainFlowAdvance() {
        service.restoreStep(100, "01");
        String line = service.nextMainLineAfterUser(100, "骂人", "您最近有没有资金备用或者转贷的打算？");
        assertEquals(null, line);
        assertEquals("01", service.currentStep(100));
    }
}
