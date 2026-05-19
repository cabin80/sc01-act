package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.activiti.engine.HistoryService;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;

@Service
public class WorkflowService {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RepositoryService repositoryService;


    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        // Deploy the process definition
        var deployments = repositoryService.createDeploymentQuery().list();
        if (deployments.isEmpty()) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/product-approval.bpmn20.xml")
                    .name("商品上线审批流程")
                    .deploy();
        }
    }

    public Map<String, Object> startProcess(String title, String productName, String productDesc, String productPrice) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("title", title);
        variables.put("productName", productName);
        variables.put("productDesc", productDesc);
        variables.put("productPrice", productPrice);
        variables.put("approvalHistory", "[]");
        variables.put("supervisorAction", "");
        variables.put("supervisorFinalAction", "");
        variables.put("managerAction", "");
        variables.put("warehouseComment", "");
        variables.put("countersign", "false");
        variables.put("status", "运营发布");

        var process = runtimeService.startProcessInstanceByKey("productApproval", variables);

        // Auto-complete the submit task (operations submit)
        var submitTask = taskService.createTaskQuery()
                .processInstanceId(process.getProcessInstanceId())
                .taskAssignee("operations")
                .singleResult();
        if (submitTask != null) {
            addComment(submitTask.getId(), "operations", "提交商品上线申请", "提交");
            taskService.complete(submitTask.getId());
            updateStatus(process.getProcessInstanceId(), "等待主管审批");
        }

        return getProcessDetail(process.getProcessInstanceId());
    }

    public List<Map<String, String>> getTasksByRole(String role) {
        var tasks = taskService.createTaskQuery()
                .taskAssignee(role)
                .orderByTaskCreateTime().desc()
                .list();

        List<Map<String, String>> result = new ArrayList<>();
        for (Task task : tasks) {
            Map<String, String> map = new HashMap<>();
            map.put("taskId", task.getId());
            map.put("taskName", task.getName());
            map.put("processInstanceId", task.getProcessInstanceId());
            map.put("createTime", task.getCreateTime() != null ? task.getCreateTime().toString() : "");
            result.add(map);
        }
        return result;
    }

    public Map<String, Object> getProcessDetail(String processInstanceId) {
        Map<String, Object> result = new HashMap<>();

        // Get process variables
        var variables = runtimeService.getVariables(processInstanceId);
        result.put("variables", variables);

        // Get current active tasks
        var activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .orderByTaskCreateTime().asc()
                .list();
        List<Map<String, String>> taskList = new ArrayList<>();
        for (Task task : activeTasks) {
            Map<String, String> tm = new HashMap<>();
            tm.put("taskId", task.getId());
            tm.put("taskName", task.getName());
            tm.put("assignee", task.getAssignee());
            tm.put("createTime", task.getCreateTime() != null ? task.getCreateTime().toString() : "");
            taskList.add(tm);
        }
        result.put("activeTasks", taskList);

        // Determine current nodes for highlighting
        List<String> currentNodeIds = new ArrayList<>();
        for (var t : activeTasks) {
            String defKey = getTaskDefinitionKey(t.getId());
            if (defKey != null) currentNodeIds.add(defKey);
        }
        // If no active tasks but process is still running, check if it's on a gateway
        if (currentNodeIds.isEmpty()) {
            var processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (processInstance != null) {
                // Process still active but no user tasks - check running activities
                var runningActivities = runtimeService.getActiveActivityIds(processInstanceId);
                if (runningActivities != null) {
                    currentNodeIds.addAll(runningActivities);
                }
            }
        }
        if (currentNodeIds.isEmpty()) {
            // Process ended
            result.put("currentNodes", List.of());
            result.put("ended", true);
        } else {
            result.put("currentNodes", currentNodeIds);
            result.put("ended", false);
        }

        // Get approval history
        var historyRaw = variables.getOrDefault("approvalHistory", "[]");
        try {
            List<Map<String, String>> approvalHistory = objectMapper.readValue(
                    historyRaw.toString(), new TypeReference<List<Map<String, String>>>() {});
            result.put("approvalHistory", approvalHistory);
        } catch (Exception e) {
            result.put("approvalHistory", List.of());
        }

        // Get process definition info
        var processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (processInstance != null) {
            result.put("processInstanceId", processInstanceId);
            result.put("running", true);

            var procDef = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(processInstance.getProcessDefinitionId())
                    .singleResult();
            if (procDef != null) {
                result.put("processDefinitionId", procDef.getId());
                result.put("processDefinitionName", procDef.getName());
            }
        } else {
            // Completed process - get from history
            var historicProcess = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (historicProcess != null) {
                result.put("processInstanceId", processInstanceId);
                result.put("running", false);
                result.put("endTime", historicProcess.getEndTime() != null ?
                        historicProcess.getEndTime().toString() : "");
            }
        }

        return result;
    }

    public Map<String, Object> completeTask(String taskId, String role, String action, String comment) {
        var task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }

        String processInstanceId = task.getProcessInstanceId();

        addComment(taskId, role, comment, action);

        switch (action) {
            case "approve": {
                if ("supervisor".equals(role)) {
                    runtimeService.setVariable(processInstanceId, "supervisorAction", "approve");
                    updateStatus(processInstanceId, "等待经理审批");
                } else if ("manager".equals(role)) {
                    runtimeService.setVariable(processInstanceId, "managerAction", "approve");
                    updateStatus(processInstanceId, "审批通过");
                }
                taskService.complete(taskId);
                break;
            }
            case "reject": {
                if ("supervisor".equals(role)) {
                    runtimeService.setVariable(processInstanceId, "supervisorAction", "reject");
                } else if ("manager".equals(role)) {
                    runtimeService.setVariable(processInstanceId, "managerAction", "reject");
                }
                updateStatus(processInstanceId, "审批驳回（" + mapRole(role) + "拒绝）");
                taskService.complete(taskId);
                break;
            }
            case "addSign": {
                runtimeService.setVariable(processInstanceId, "supervisorAction", "addSign");
                runtimeService.setVariable(processInstanceId, "countersign", "true");
                updateStatus(processInstanceId, "主管发起加签");
                taskService.complete(taskId);
                break;
            }
            case "warehouse_confirm": {
                runtimeService.setVariable(processInstanceId, "warehouseComment", comment);
                updateStatus(processInstanceId, "库管已确认，等待主管复核");
                taskService.complete(taskId);
                break;
            }
            case "supervisor_final_approve": {
                runtimeService.setVariable(processInstanceId, "supervisorFinalAction", "approve");
                updateStatus(processInstanceId, "等待经理审批");
                taskService.complete(taskId);
                break;
            }
            case "supervisor_final_reject": {
                runtimeService.setVariable(processInstanceId, "supervisorFinalAction", "reject");
                updateStatus(processInstanceId, "审批驳回（主管复核拒绝）");
                taskService.complete(taskId);
                break;
            }
            default:
                throw new RuntimeException("Unknown action: " + action);
        }

        return getProcessDetail(processInstanceId);
    }

    public boolean isProcessEnded(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult() == null;
    }

    private void addComment(String taskId, String role, String comment, String action) {
        String actionLabel;
        switch (action) {
            case "approve": actionLabel = "同意"; break;
            case "reject": actionLabel = "拒绝"; break;
            case "addSign": actionLabel = "加签 - 询问库存"; break;
            case "warehouse_confirm": actionLabel = "确认库存"; break;
            case "supervisor_final_approve": actionLabel = "同意（复核）"; break;
            case "supervisor_final_reject": actionLabel = "拒绝（复核）"; break;
            default: actionLabel = action;
        }

        var task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task != null) {
            String processInstanceId = task.getProcessInstanceId();

            var historyRaw = runtimeService.getVariable(processInstanceId, "approvalHistory");
            List<Map<String, String>> history;
            try {
                history = objectMapper.readValue(
                        historyRaw != null ? historyRaw.toString() : "[]",
                        new TypeReference<List<Map<String, String>>>() {});
            } catch (Exception e) {
                history = new ArrayList<>();
            }

            // For addSign, add a special entry
            if ("addSign".equals(action)) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("time", new Date().toString());
                entry.put("role", mapRole(role));
                entry.put("action", actionLabel);
                entry.put("comment", "主管询问库管库存情况");
                history.add(entry);
            } else {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("time", new Date().toString());
                entry.put("role", mapRole(role));
                entry.put("action", actionLabel);
                entry.put("comment", comment);
                history.add(entry);
            }

            try {
                runtimeService.setVariable(processInstanceId, "approvalHistory",
                        objectMapper.writeValueAsString(history));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void updateStatus(String processInstanceId, String status) {
        runtimeService.setVariable(processInstanceId, "status", status);
    }

    private String getTaskDefinitionKey(String taskId) {
        var task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task != null) {
            // We need to find the definition key from execution
            var execution = runtimeService.createExecutionQuery()
                    .executionId(task.getExecutionId())
                    .singleResult();
            if (execution != null) {
                // The activity ID from execution tells us current node
                return execution.getActivityId();
            }
        }
        return null;
    }

    private String mapRole(String role) {
        switch (role) {
            case "operations": return "运营";
            case "supervisor": return "主管";
            case "warehouse": return "库管";
            case "manager": return "经理";
            default: return role;
        }
    }
}
