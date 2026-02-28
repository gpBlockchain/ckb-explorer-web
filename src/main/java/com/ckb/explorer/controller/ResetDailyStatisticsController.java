package com.ckb.explorer.controller;

import com.ckb.explorer.feign.WorkerService;
import com.ckb.explorer.common.dto.ResponseInfo;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.Resource;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/v1/manual_trigger")
public class ResetDailyStatisticsController {

  @Resource
  WorkerService workerService;

  @Value("${reset.statistics.password}")
  private String expectedPassword;

  /**
   * 验证令牌是否正确
   * @param token 传入的令牌
   * @return 验证结果
   */
  private boolean validateToken(String token) {
    return expectedPassword != null && expectedPassword.equals(token);
  }

  /**
   * 手动重置每日统计任务的个别字段
   * @param field 字段名
   * @param token 访问令牌（从请求头获取）
   * @return 操作结果
   */
  @PostMapping("reset_daily_statistics")
  public ResponseInfo<Boolean> reset(@RequestParam String field, @RequestHeader("X-Auth-Token") String token) {
    // 验证令牌
    if (!validateToken(token)) {
      return ResponseInfo.FAILURE("Invalid token");
    }
    
    // 验证查询参数
    if(field == null || field.isEmpty()){
      return ResponseInfo.FAILURE("field is empty");
    }

    // 从缓存门面获取数据
    var result = workerService.reset(field);

    // 返回成功响应
    return ResponseInfo.SUCCESS(result);
  }

  /**
   * 手动触发每日统计任务
   * @param startDate 开始日期
   * @param token 访问令牌（从请求头获取）
   * @return 操作结果
   */
  @PostMapping("daily_statistics")
  public ResponseInfo<Boolean> manualTriggerDailyStatistics(@RequestParam(required = false) LocalDate startDate, @RequestHeader("X-Auth-Token") String token) {
    // 验证令牌
    if (!validateToken(token)) {
      return ResponseInfo.FAILURE("Invalid token");
    }
    
    var result = workerService.manualTriggerDailyStatistics(startDate);

    // 返回成功响应
    return ResponseInfo.SUCCESS(result);
  }

  /**
   * 手动触发每日统计任务
   * @param startDate 开始日期
   * @param token 访问令牌（从请求头获取）
   * @return 操作结果
   */
  @PostMapping("udt_daily_statistics")
  public ResponseInfo<Boolean> manualTriggerUdtDailyStatistics(@RequestParam(required = false) LocalDate startDate, @RequestHeader("X-Auth-Token") String token) {
    // 验证令牌
    if (!validateToken(token)) {
      return ResponseInfo.FAILURE("Invalid token");
    }
    
    var result = workerService.manualTriggerUdtDailyStatistics(startDate);

    // 返回成功响应
    return ResponseInfo.SUCCESS(result);
  }

  /**
   * 手动触发矿工每日统计任务
   * @param startDate 开始日期
   * @param token 访问令牌（从请求头获取）
   * @return 操作结果
   */
  @PostMapping("miner_daily_statistics")
  public ResponseInfo<Boolean> manualTriggerMinerDailyStatistics(@RequestParam(required = false) LocalDate startDate, @RequestHeader("X-Auth-Token") String token) {
    // 验证令牌
    if (!validateToken(token)) {
      return ResponseInfo.FAILURE("Invalid token");
    }

    var result = workerService.manualTriggerMinerDailyStatistics(startDate);

    // 返回成功响应
    return ResponseInfo.SUCCESS(result);
  }
}
