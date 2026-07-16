package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("dialog_training_qa")
public class DialogTrainingQa {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer kbId;
    private String question;
    private String standardAnswer;
    /** 1人工修正 2优质样本 3负样本 */
    private Integer dataType;
    private BigDecimal weight;
    private Integer status;
    private Integer sourceCallId;
    private String remark;
    /** 主线节点排序（remark=flow:XX 时有效） */
    private Integer flowOrder;
    /** 标准答录音（8k 电话 wav 本地路径） */
    private String answerWavPath;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
