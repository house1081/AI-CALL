package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("prerecord_faq")
public class PrerecordFaq {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 展示用原话 */
    private String questionDisplay;
    /** 规范化问题（匹配键） */
    private String questionNorm;
    /** 利率/额度/征信/手续费/办理流程/放款时效/其他 */
    private String category;
    /** 逗号分隔关键词 */
    private String keywords;
    /** 标准应答文案（与录音一致） */
    private String answerText;
    /** high_freq | cold | transfer_only */
    private String tier;
    /** 历史挖掘频次 */
    private Integer hitCount;
    /** 运行时匹配次数 */
    private Integer matchCount;
    /** 触发转顾问次数 */
    private Integer transferCount;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
