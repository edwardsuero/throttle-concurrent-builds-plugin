package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractProject;
import hudson.model.ParameterValue;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.queue.WorkUnit;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.security.ACL;
import hudson.security.NotSerilizableSecurityContext;
import hudson.model.Action;
import hudson.model.ParametersAction;
import hudson.model.queue.SubTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;

import jenkins.model.Jenkins;

@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        if (Jenkins.getAuthentication() == ACL.SYSTEM) {
            return canTakeImpl(node, task);
        }
        
        // Throttle-concurrent-builds requires READ permissions for all projects.
        SecurityContext orig = SecurityContextHolder.getContext();
        NotSerilizableSecurityContext auth = new NotSerilizableSecurityContext();
        auth.setAuthentication(ACL.SYSTEM);
        SecurityContextHolder.setContext(auth);
        
        try {
            return canTakeImpl(node, task);
        } finally {
            SecurityContextHolder.setContext(orig);
        }
    }
    
    private CauseOfBlockage canTakeImpl(Node node, Task task) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        ThrottleJobProperty tjp = getThrottleJobProperty(task);
        
        // Handle multi-configuration filters
        if (!shouldBeThrottled(task, tjp)) {
            return null;
        }

        if (tjp!=null && tjp.getThrottleEnabled()) {
            CauseOfBlockage cause = canRunImpl(task, tjp);
            if (cause != null) {
            	return cause;
            }

            if (tjp.getThrottleOption().equals("project")) {
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, task);

                    // This would mean that there are as many or more builds currently running than are allowed.
                    if (runCount >= maxConcurrentPerNode) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                    }
                }
            }
            else if (tjp.getThrottleOption().equals("category")) {
                // If the project is in one or more categories...
                if (tjp.getCategories() != null && !tjp.getCategories().isEmpty()) {
                    for (String catNm : tjp.getCategories()) {
                        // Quick check that catNm itself is a real string.
                        if (catNm != null && !catNm.equals("")) {
                            List<Task> categoryTasks = ThrottleJobProperty.getCategoryTasks(catNm);

                            ThrottleJobProperty.ThrottleCategory category =
                                ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);

                            // Double check category itself isn't null
                            if (category != null) {
                                // Max concurrent per node for category
                                int maxConcurrentPerNode = getMaxConcurrentPerNodeBasedOnMatchingLabels(
                                    node, category, category.getMaxConcurrentPerNode().intValue());
                                if (maxConcurrentPerNode > 0) {
                                    int runCount = 0;
                                    for (Task catTask : categoryTasks) {
                                        if (jenkins.getQueue().isPending(catTask)) {
                                            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                        }
                                        runCount += buildsOfProjectOnNode(node, catTask);
                                    }
                                    // This would mean that there are as many or more builds currently running than are allowed.
                                    if (runCount >= maxConcurrentPerNode) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    // @Override on jenkins 4.127+ , but still compatible with 1.399
    public CauseOfBlockage canRun(Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            if (tjp.isLimitOneJobWithMatchingParams() && isAnotherBuildWithSameParametersRunningOnAnyNode(item)) {
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_OnlyOneWithMatchingParameters());
            } else if (tjp.isLimitOneJobWithMatchingParams() && isBlockedAndNotFirstInLine(item)) {
                // This build is not first in line, so keep blocked until first in line starts so that we can check parameters.
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_NotFirstInLine());
            }
            return canRun(item.task, tjp);
        }
        return null;
    }
    
    @Nonnull
    private ThrottleMatrixProjectOptions getMatrixOptions(Task task) {
        ThrottleJobProperty tjp = getThrottleJobProperty(task);
        if (tjp == null){
        	return ThrottleMatrixProjectOptions.DEFAULT;       
        }
        ThrottleMatrixProjectOptions matrixOptions = tjp.getMatrixOptions();
        return matrixOptions != null ? matrixOptions : ThrottleMatrixProjectOptions.DEFAULT;
    }
    
    private boolean shouldBeThrottled(@Nonnull Task task, @CheckForNull ThrottleJobProperty tjp) {
       if (tjp == null) {
    	   return false;
       }
       if (!tjp.getThrottleEnabled()) { 
    	   return false;
       }
       
       // Handle matrix options
       ThrottleMatrixProjectOptions matrixOptions = tjp.getMatrixOptions();
       if (matrixOptions == null) {
    	   matrixOptions = ThrottleMatrixProjectOptions.DEFAULT;
       }
       if (!matrixOptions.isThrottleMatrixConfigurations() && task instanceof MatrixConfiguration) {
            return false;
       } 
       if (!matrixOptions.isThrottleMatrixBuilds()&& task instanceof MatrixProject) {
            return false;
       }
       
       // Allow throttling by default
       return true;
    }

    public CauseOfBlockage canRun(Task task, ThrottleJobProperty tjp) {
        if (Jenkins.getAuthentication() == ACL.SYSTEM) {
            return canRunImpl(task, tjp);
        }
        
        // Throttle-concurrent-builds requires READ permissions for all projects.
        SecurityContext orig = SecurityContextHolder.getContext();
        NotSerilizableSecurityContext auth = new NotSerilizableSecurityContext();
        auth.setAuthentication(ACL.SYSTEM);
        SecurityContextHolder.setContext(auth);
        
        try {
            return canRunImpl(task, tjp);
        } finally {
            SecurityContextHolder.setContext(orig);
        }
    }
    
    private CauseOfBlockage canRunImpl(Task task, ThrottleJobProperty tjp) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        if (!shouldBeThrottled(task, tjp)) {
            return null;
        }
        if (jenkins.getQueue().isPending(task)) {
            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
        }
        if (tjp.getThrottleOption().equals("project")) {
            if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                int totalRunCount = buildsOfProjectOnAllNodes(task);

                if (totalRunCount >= maxConcurrentTotal) {
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                }
            }
        }
        // If the project is in one or more categories...
        else if (tjp.getThrottleOption().equals("category")) {
            if (tjp.getCategories() != null && !tjp.getCategories().isEmpty()) {
                for (String catNm : tjp.getCategories()) {
                    // Quick check that catNm itself is a real string.
                    if (catNm != null && !catNm.equals("")) {
                        List<Task> categoryTasks = ThrottleJobProperty.getCategoryTasks(catNm);

                        ThrottleJobProperty.ThrottleCategory category =
                            ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);

                        // Double check category itself isn't null
                        if (category != null) {
                            if (category.getMaxConcurrentTotal().intValue() > 0) {
                                int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                                int totalRunCount = 0;

                                for (Task catTask : categoryTasks) {
                                    if (jenkins.getQueue().isPending(catTask)) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                    }
                                    totalRunCount += buildsOfProjectOnAllNodes(catTask);
                                }

                                if (totalRunCount >= maxConcurrentTotal) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                                }
                            }

                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isAnotherBuildWithSameParametersRunningOnAnyNode(Queue.Item item) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        if (isAnotherBuildWithSameParametersRunningOnNode(jenkins, item)) {
            return true;
        }

        for (Node node : jenkins.getNodes()) {
            if (isAnotherBuildWithSameParametersRunningOnNode(node, item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnotherBuildWithSameParametersRunningOnNode(Node node, Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        if (tjp == null) {
            // If the property has been ocasionally deleted by this call, 
            // it does not make sense to limit the throttling by parameter.
            return false;
        }
        Computer computer = node.toComputer();
        List<String> paramsToCompare = tjp.getParamsToCompare();
        List<ParameterValue> itemParams = getParametersFromQueueItem(item);

        if (paramsToCompare.size() > 0) {
            itemParams = doFilterParams(paramsToCompare, itemParams);
        }

        if (computer != null) {
            for (Executor exec : computer.getExecutors()) {
                if (item != null && item.task != null) {
                    // TODO: refactor into a nameEquals helper method
                    final Queue.Executable currentExecutable = exec.getCurrentExecutable();
                    final SubTask parentTask = currentExecutable != null ? currentExecutable.getParent() : null;
                    if (currentExecutable != null && parentTask != null && parentTask.getOwnerTask() != null) {
                        if (tjp.getThrottleOption().equalsIgnoreCase("category") && !isInMutualCategory(tjp, getThrottleJobProperty(parentTask.getOwnerTask()))) {
                            continue;
                        } else if (tjp.getThrottleOption().equalsIgnoreCase("project") && !parentTask.getOwnerTask().getName().equals(item.task.getName())) {
                            continue;
                        }
                        List<ParameterValue> executingUnitParams = getParametersFromWorkUnit(exec.getCurrentWorkUnit());
                        executingUnitParams = doFilterParams(paramsToCompare, executingUnitParams);

                        if (executingUnitParams.containsAll(itemParams)) {
                            LOGGER.log(Level.FINE, "build (" + exec.getCurrentWorkUnit() +
                                    ") with identical parameters (" +
                                    executingUnitParams + ") is already running.");
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if two jobs are in a mutual category.
     * @param jobBeingChecked
     * @param executingJob
     * @return
     */
    private boolean isInMutualCategory(ThrottleJobProperty jobBeingChecked, ThrottleJobProperty executingJob) {

        if (jobBeingChecked != null && executingJob != null) {
            for (String category : jobBeingChecked.getCategories()) {
                if (executingJob.getCategories().contains(category)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if item is blocked. If so, check if this item is first in line among blocked.
     * @param itemBeingChecked
     * @return
     */
    private boolean isBlockedAndNotFirstInLine(Queue.Item itemBeingChecked) {

        if (itemBeingChecked != null && itemBeingChecked.isBlocked()) {
            final Jenkins jenkins = Jenkins.getActiveInstance();
            Queue queue = jenkins.getQueue();

            List<Queue.BlockedItem> blockedItems = new ArrayList<>();
            for (Queue.Item item : queue.getPendingItems()) { //List of Buildable items to list of blocked items.
                if (item.isBlocked()) {
                    blockedItems.add((Queue.BlockedItem) item);
                }
            }

            if (blockedItems.size() == 1) {
                return false;
            } else if (blockedItems.size() > 1) {
                queue.getSorter().sortBlockedItems(blockedItems); // Sort the blocked items.
                if (!blockedItems.get(0).equals(itemBeingChecked)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Filter job parameters to only include parameters used for throttling
     * @param params
     * @param OriginalParams
     * @return
     */
    private List<ParameterValue> doFilterParams(List<String> params, List<ParameterValue> OriginalParams) {
        if (params.isEmpty()) {
            return OriginalParams;
        }

        List<ParameterValue> newParams = new ArrayList<ParameterValue>();

        for (ParameterValue p : OriginalParams) {
            if (params.contains(p.getName())) {
                newParams.add(p);
            }
        }
        return newParams;
    }

    public List<ParameterValue> getParametersFromWorkUnit(WorkUnit unit) {
        List<ParameterValue> paramsList = new ArrayList<ParameterValue>();

        if (unit != null && unit.context != null && unit.context.actions != null) {
            List<Action> actions = unit.context.actions;
            for (Action action : actions) {
                if (action instanceof ParametersAction) {
                    ParametersAction params = (ParametersAction) action;
                    if (params != null) {
                        paramsList = params.getParameters();
                    }
                }
            }
        }
        return paramsList;
    }

    public List<ParameterValue> getParametersFromQueueItem(Queue.Item item) {
        List<ParameterValue> paramsList;

        ParametersAction params = item.getAction(ParametersAction.class);
        if (params != null) {
            paramsList = params.getParameters();
        }
        else
        {
            paramsList  = new ArrayList<ParameterValue>();
        }
        return paramsList;
    }


    @CheckForNull
    private ThrottleJobProperty getThrottleJobProperty(Task task) {
        if (task instanceof Job) {
            Job<?,?> p = (Job<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = ((MatrixConfiguration)task).getParent();
            }
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);
            return tjp;
        }
        return null;
    }

    private int buildsOfProjectOnNode(Node node, Task task) {
        if (!shouldBeThrottled(task, getThrottleJobProperty(task))) {
            return 0;
        }

        int runCount = 0;
        LOGGER.log(Level.FINE, "Checking for builds of {0} on node {1}", new Object[] {task.getName(), node.getDisplayName()});

        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        Computer computer = node.toComputer();
        if (computer != null) { //Not all nodes are certain to become computers, like nodes with 0 executors.
            // Count flyweight tasks that might not consume an actual executor.
            for (Executor e : computer.getOneOffExecutors()) {
                runCount += buildsOnExecutor(task, e);
            }

            for (Executor e : computer.getExecutors()) {
                runCount += buildsOnExecutor(task, e);
            }
        }

        return runCount;
    }

    private int buildsOfProjectOnAllNodes(Task task) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        int totalRunCount = buildsOfProjectOnNode(jenkins, task);

        for (Node node : jenkins.getNodes()) {
            totalRunCount += buildsOfProjectOnNode(node, task);
        }
        return totalRunCount;
    }

    private int buildsOnExecutor(Task task, Executor exec) {
        int runCount = 0;
        final Queue.Executable currentExecutable = exec.getCurrentExecutable();
        if (currentExecutable != null && task.equals(currentExecutable.getParent())) {
            runCount++;
        }

        return runCount;
    }

    /**
     * @param node to compare labels with.
     * @param category to compare labels with.
     * @param maxConcurrentPerNode to return if node labels mismatch.
     * @return maximum concurrent number of builds per node based on matching labels, as an int.
     * @author marco.miller@ericsson.com
     */
    private int getMaxConcurrentPerNodeBasedOnMatchingLabels(
        Node node, ThrottleJobProperty.ThrottleCategory category, int maxConcurrentPerNode)
    {
        List<ThrottleJobProperty.NodeLabeledPair> nodeLabeledPairs = category.getNodeLabeledPairs();
        int maxConcurrentPerNodeLabeledIfMatch = maxConcurrentPerNode;
        boolean nodeLabelsMatch = false;
        Set<LabelAtom> nodeLabels = node.getAssignedLabels();

        for(ThrottleJobProperty.NodeLabeledPair nodeLabeledPair: nodeLabeledPairs) {
            String throttledNodeLabel = nodeLabeledPair.getThrottledNodeLabel();
            if(!nodeLabelsMatch && !throttledNodeLabel.isEmpty()) {
                for(LabelAtom aNodeLabel: nodeLabels) {
                    String nodeLabel = aNodeLabel.getDisplayName();
                    if(nodeLabel.equals(throttledNodeLabel)) {
                        maxConcurrentPerNodeLabeledIfMatch = nodeLabeledPair.getMaxConcurrentPerNodeLabeled().intValue();
                        LOGGER.log(Level.FINE, "node labels match; => maxConcurrentPerNode'' = {0}", maxConcurrentPerNodeLabeledIfMatch);
                        nodeLabelsMatch = true;
                        break;
                    }
                }
            }
        }
        if(!nodeLabelsMatch) {
            LOGGER.fine("node labels mismatch");
        }
        return maxConcurrentPerNodeLabeledIfMatch;
    }

    private static final Logger LOGGER = Logger.getLogger(ThrottleQueueTaskDispatcher.class.getName());
}
