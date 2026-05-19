package com.example.demo.controller;

import com.example.demo.service.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    @Autowired
    private WorkflowService workflowService;

    /**
     * Start a new product approval process
     */
    @PostMapping("/process/start")
    public ResponseEntity<Map<String, Object>> startProcess(@RequestBody Map<String, String> params) {
        String title = params.getOrDefault("title", "商品上线审批");
        String productName = params.getOrDefault("productName", "iPhone 15 Pro 256G");
        String productDesc = params.getOrDefault("productDesc", "新型手机上市，五一节活动款");
        String productPrice = params.getOrDefault("productPrice", "5999");

        var result = workflowService.startProcess(title, productName, productDesc, productPrice);
        return ResponseEntity.ok(result);
    }

    /**
     * Get tasks for a specific role
     */
    @GetMapping("/tasks")
    public ResponseEntity<List<Map<String, String>>> getTasks(@RequestParam String role) {
        var tasks = workflowService.getTasksByRole(role);
        return ResponseEntity.ok(tasks);
    }

    /**
     * Get process detail by process instance ID
     */
    @GetMapping("/process/{processInstanceId}")
    public ResponseEntity<Map<String, Object>> getProcess(@PathVariable String processInstanceId) {
        var detail = workflowService.getProcessDetail(processInstanceId);
        return ResponseEntity.ok(detail);
    }

    /**
     * Complete a task with action and comment
     * Actions: approve, reject, addSign, warehouse_confirm, supervisor_final_approve, supervisor_final_reject
     */
    @PostMapping("/tasks/{taskId}/complete")
    public ResponseEntity<Map<String, Object>> completeTask(
            @PathVariable String taskId,
            @RequestBody Map<String, String> params) {

        String role = params.get("role");
        String action = params.get("action");
        String comment = params.getOrDefault("comment", "");

        var result = workflowService.completeTask(taskId, role, action, comment);
        return ResponseEntity.ok(result);
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
