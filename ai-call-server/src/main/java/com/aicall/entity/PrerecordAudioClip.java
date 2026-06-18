package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("prerecord_audio_clip")
public class PrerecordAudioClip {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** null=全局片段（缓冲/转顾问等） */
    private Long faqId;
    /** buffer | answer | closing | transfer | opening | refuse | end */
    private String clipType;
    private String textContent;
    private String wavPath;
    private Integer variantNo;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
