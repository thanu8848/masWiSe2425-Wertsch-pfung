package de.htw_berlin.fb4.mas.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES;
import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * Implementierung eines {@link ExternalTaskHandler}, der für jeden {@link ExternalTask} einen UiPath-Robot auf dem lokalen Rechner startet.
 * Der Prozess muss dazu veröffentlicht sein, entweder über den Orchestrator oder lokal als nupkg-Paket.
 * <p>
 * Über den Orchestrator veröffentlichen und hier verwenden:
 * <ol>
 *     <li>In UiPath Studio <i>Veröffentlichen</i></li>
 *     <li><i>Paketname</i> festlegen</li>
 *     <li>Unter <i>Veröffentlichungsoptionen</i> bei <i>Veröffentlichen in</i> den Wert <i>Orchestrator</i> auswählen</li>
 *     <li>Auf <i>Veröffentlichen</i> klicken</li>
 *     <li>Prüfen, dass der Prozess in UiPath Assistant installiert ist</li>
 *     <li>Diesen ExternalTaskHandler mit {@code new RunUiPathRobot("Paketname")} (siehe Punkt 2) erstellen und verwenden</li>
 * </ol>
 * <p>
 * Lokal als nupkg-Paket veröffentlichen und hier verwenden:
 * <ol>
 *     <li>In UiPath Studio <i>Veröffentlichen</i></li>
 *     <li><i>Paketname</i> festlegen</li>
 *     <li>Unter <i>Veröffentlichungsoptionen</i> bei <i>Veröffentlichen in</i> den Wert <i>Benutzerdefiniert</i> auswählen</li>
 *     <li>Unter <i>Veröffentlichungsoptionen</i> bei <i>Benutzerdefinierte URL</i> ein Verzeichnis auswählen, in dem UiPath die nupkg-Datei erstellen soll</li>
 *     <li>Auf <i>Veröffentlichen</i> klicken</li>
 *     <li>Das ausgewählte Verzeichnis öffnen und den Namen der nupkg-Datei ermitteln</li>
 *     <li>Diesen ExternalTaskHandler mit {@code new RunUiPathRobot(Path.of("c:\\Pfad\zum\\Prozess.nupkg"))} (siehe Punkt 6) erstellen und verwenden</li>
 * </ol>
 * <p>
 * Siehe auch: <a href="https://docs.uipath.com/robot/standalone/2023.10/user-guide/command-line-interface">Robot Command Line Interface</a>
 */
public class RunUiPathRobot implements ExternalTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(RunUiPathRobot.class);

    private static final ObjectMapper objectMapper = new ObjectMapper().configure(ALLOW_SINGLE_QUOTES, true);

    private static final Path uiRobotExecutable = findUiRobotExecutable();

    private final Path projectPackage;

    private final String processName;

    /**
     * Erstellt einen {@link ExternalTaskHandler}, der für jeden {@link ExternalTask} einen UiPath-Robot startet
     * und den angegebenen Prozess ausführt.
     */
    public RunUiPathRobot(String processName) {
        this.projectPackage = null;
        this.processName = processName;
    }

    /**
     * Erstellt einen {@link ExternalTaskHandler}, der für jeden {@link ExternalTask} einen UiPath-Robot startet
     * und die angegebene nupkg-Datei ausführt.
     */
    public RunUiPathRobot(Path projectPackage) {
        if (!Files.exists(projectPackage)) {
            throw new IllegalArgumentException(projectPackage + " does not exist");
        }

        if (!Files.isRegularFile(projectPackage)) {
            throw new IllegalArgumentException(projectPackage + " is not a regular file");
        }

        this.projectPackage = projectPackage;
        this.processName = null;
    }

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        log.info("Handling external task (Task ID: {} - Process Instance ID {})", externalTask.getId(), externalTask.getProcessInstanceId());

        try {
            Process uiRobotProcess = startUiRobotProcess(externalTask);

            boolean hasExited = uiRobotProcess.waitFor(10, MINUTES);
            if (!hasExited) {
                handleTimedOutProcess(externalTask, externalTaskService, uiRobotProcess);
                return;
            }

            boolean exitedSuccessfully = uiRobotProcess.exitValue() == 0;
            if (exitedSuccessfully) {
                handleSuccessfullyExecutedProcess(externalTask, externalTaskService, uiRobotProcess);
            } else {
                handleFailure(externalTask, externalTaskService, uiRobotProcess);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            handleFailure(externalTask, externalTaskService, e);
        }
    }

    private Process startUiRobotProcess(ExternalTask externalTask) throws Exception {
        List<String> processArguments = createUiPathRobotProcessArguments(externalTask);

        log.info("Starting UiPath Robot process {}", processArguments);
        return new ProcessBuilder().command(processArguments).start();
    }

    private List<String> createUiPathRobotProcessArguments(ExternalTask externalTask) {
        List<String> arguments = new ArrayList<>();

        arguments.add(uiRobotExecutable.toAbsolutePath().toString());
        arguments.add("execute");

        if (projectPackage != null) {
            arguments.add("--file");
            arguments.add(projectPackage.toAbsolutePath().toString());
        } else if (processName != null) {
            arguments.add("--process");
            arguments.add(processName);
        }

        String input = convertVariablesAsRobotInputArgument(externalTask);
        arguments.add("--input");
        arguments.add(input);

        return arguments;
    }

    private static String convertVariablesAsRobotInputArgument(ExternalTask externalTask) {
        Map<String, Object> convertibleVariables = new HashMap<>();
        externalTask.getAllVariables().forEach((name, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                convertibleVariables.put(name, value);
            }
        });

        JsonNode inputArguments = objectMapper.valueToTree(convertibleVariables);
        return inputArguments.toString().replace('"', '\'');
    }

    private void handleSuccessfullyExecutedProcess(ExternalTask externalTask, ExternalTaskService externalTaskService, Process uiRobotProcess) {
        log.info("UiPath Robot successfully executed");

        Map<String, Object> variables = new HashMap<>();
        try {
            JsonNode outputVariables = objectMapper.readTree(uiRobotProcess.getInputStream());
            objectMapper.convertValue(outputVariables, new TypeReference<Map<String, Object>>() {
            }).forEach((name, value) -> {
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    variables.put(name, value);
                }
            });
        } catch (IOException e) {
            log.warn("Could not read output variables", e);
        }

        log.info("Completing task with variables = {}", variables);
        externalTaskService.complete(externalTask, variables);
    }

    private void handleTimedOutProcess(ExternalTask externalTask, ExternalTaskService externalTaskService, Process uiRobotProcess) {
        log.warn("UiPath Robot timed out, killing process");
        uiRobotProcess.destroy();

        String errorMessage = "UiPath Robot Timeout";
        String errorDetails = "Command: " + uiRobotProcess.info().commandLine().orElse("<unknown>");

        createIncident(externalTask, externalTaskService, errorMessage, errorDetails);
    }

    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Process uiRobotProcess) {
        log.error("UiPath Robot failed with exit value {}", uiRobotProcess.exitValue());

        String errorMessage = "UiPath Robot Failed";

        StringBuilder errorDetailsBuilder = new StringBuilder();
        errorDetailsBuilder.append("Command: ").append(uiRobotProcess.info().commandLine().orElse("<unknown>")).append('\n');
        errorDetailsBuilder.append("Exit Value: ").append(uiRobotProcess.exitValue()).append('\n');
        errorDetailsBuilder.append('\n');

        try {
            errorDetailsBuilder.append("Output:\n");
            errorDetailsBuilder.append(new String(uiRobotProcess.getInputStream().readAllBytes()));
            errorDetailsBuilder.append('\n');
        } catch (IOException e) {
            log.debug("Could not read stdout of process", e);
        }

        try {
            errorDetailsBuilder.append("Error:\n");
            errorDetailsBuilder.append(new String(uiRobotProcess.getErrorStream().readAllBytes()));
        } catch (IOException e) {
            log.debug("Could not read stderr of process", e);
        }

        createIncident(externalTask, externalTaskService, errorMessage, errorDetailsBuilder.toString());
    }

    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception exception) {
        log.error("Failed to start UiPath Robot", exception);

        String errorMessage = "Failed to Start UiPath Robot";

        StringWriter stackTraceWriter = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTraceWriter));
        String errorDetails = stackTraceWriter.toString();

        createIncident(externalTask, externalTaskService, errorMessage, errorDetails);
    }

    private static void createIncident(ExternalTask externalTask, ExternalTaskService externalTaskService, String errorMessage, String errorDetails) {
        int retries = 0;
        long retryTimeout = 0;

        log.info("Creating incident for process instance {} with message '{}'", externalTask.getProcessInstanceId(), errorMessage);
        externalTaskService.handleFailure(externalTask, errorMessage, errorDetails, retries, retryTimeout);
    }

    private static Path findUiRobotExecutable() {
        List<Path> installationFolders = List.of(
                Path.of(System.getenv("LOCALAPPDATA"), "Programs"),
                Path.of(System.getenv("ProgramFiles")),
                Path.of(System.getenv("ProgramFiles(x86)"))
        );

        return installationFolders.stream()
                .map(installationFolder -> installationFolder.resolve("UiPath\\Studio\\UiRobot.exe"))
                .filter(Files::isRegularFile)
                .map(Path::toAbsolutePath)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Could not locate UiRobot.exe in " + installationFolders));
    }
}
