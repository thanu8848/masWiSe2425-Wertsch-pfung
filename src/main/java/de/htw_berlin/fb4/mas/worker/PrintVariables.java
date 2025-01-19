package de.htw_berlin.fb4.mas.worker;

import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrintVariables implements ExternalTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(PrintVariables.class);

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        log.info("Handling external task (Task ID: {} - Process Instance ID {})", externalTask.getId(), externalTask.getProcessInstanceId());

        externalTask.getAllVariables().forEach((name, value) -> {
            System.out.println(name + " = " + value);
        });

        externalTaskService.complete(externalTask);
    }
}
