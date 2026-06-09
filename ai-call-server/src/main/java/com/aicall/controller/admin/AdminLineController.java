package com.aicall.controller.admin;

import com.aicall.common.BizException;
import com.aicall.common.PageResult;
import com.aicall.common.Result;
import com.aicall.entity.Line;
import com.aicall.mapper.LineMapper;
import com.aicall.service.LineOutboundRouteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/line")
@RequiredArgsConstructor
public class AdminLineController {

    private final LineMapper lineMapper;
    private final LineOutboundRouteService lineOutboundRouteService;

    @GetMapping("/list")
    public Result<PageResult<Line>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String sipAccount,
            @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<Line> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(sipAccount)) {
            q.like(Line::getSipAccount, sipAccount);
        }
        if (status != null) {
            q.eq(Line::getStatus, status);
        }
        q.orderByDesc(Line::getId);
        Page<Line> p = lineMapper.selectPage(new Page<>(page, pageSize), q);
        p.getRecords().forEach(l -> l.setSipPassword(null));
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

    @PostMapping("/save")
    public Result<Void> save(@RequestBody Line line) {
        if (!StringUtils.hasText(line.getSipAccount())) {
            throw new BizException("SIP账号不能为空");
        }
        if (line.getDailyCallLimit() != null && (line.getDailyCallLimit() < 100 || line.getDailyCallLimit() > 10000)) {
            throw new BizException("线路日呼上限须在100-10000次之间");
        }
        long cnt = lineMapper.selectCount(new LambdaQueryWrapper<Line>()
                .eq(Line::getSipAccount, line.getSipAccount())
                .ne(line.getId() != null, Line::getId, line.getId()));
        if (cnt > 0) {
            throw new BizException("SIP账号已存在");
        }
        validateSip(line);
        if (line.getId() == null) {
            if (!StringUtils.hasText(line.getSipPassword())) {
                throw new BizException("SIP密码不能为空（网关注册密码，明文保存供 FS 对接）");
            }
            line.setCurrentConcurrent(0);
            line.setTodayCallCount(0);
            if (line.getStatus() == null) {
                line.setStatus(1);
            }
            lineMapper.insert(line);
        } else {
            Line old = lineMapper.selectById(line.getId());
            if (!StringUtils.hasText(line.getSipPassword())) {
                line.setSipPassword(old.getSipPassword());
            }
            lineMapper.updateById(line);
        }
        return Result.ok();
    }

    @PostMapping("/validate")
    public Result<Map<String, Object>> validate(@RequestBody Line line) {
        validateSip(line);
        return Result.ok(Map.of("valid", true, "message", "SIP 配置格式通过；外呼时 gateway 名 = SIP账号，须与 FS sofia 配置一致"));
    }

    @GetMapping("/{id}/outbound-route")
    public Result<Map<String, Object>> outboundRoute(@PathVariable Integer id) {
        LineOutboundRouteService.LineRoute route = lineOutboundRouteService.requireRoute(id);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("lineId", route.getLineId());
        m.put("gatewayName", route.getGatewayName());
        m.put("sipAddress", route.getSipAddress());
        m.put("sipHost", route.getSipHost());
        m.put("sipPort", route.getSipPort());
        m.put("hint", "FreeSWITCH 外呼使用 sofia/gateway/" + route.getGatewayName() + "/被叫号码");
        return Result.ok(m);
    }

    private void validateSip(Line line) {
        if (!StringUtils.hasText(line.getSipAddress()) || !line.getSipAddress().contains(":")) {
            throw new BizException("SIP地址格式错误，示例：xxx.aliyuncs.com:5060");
        }
    }

    @PostMapping("/status/{id}")
    public Result<Void> status(@PathVariable Integer id, @RequestParam Integer status) {
        Line line = lineMapper.selectById(id);
        line.setStatus(status);
        lineMapper.updateById(line);
        return Result.ok();
    }
}
