package org.cloudsimplus.examples.autoscaling;

import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.Simulation;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.ResourceProvisionerSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.resources.Processor;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.util.MathUtil;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelStochastic;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.autoscaling.HorizontalVmScaling;
import org.cloudsimplus.autoscaling.VerticalVmScaling;
import org.cloudsimplus.autoscaling.VerticalVmScalingSimple;
import org.cloudsimplus.autoscaling.resources.ResourceScaling;
import org.cloudsimplus.autoscaling.resources.ResourceScalingGradual;
import org.cloudsimplus.autoscaling.resources.ResourceScalingInstantaneous;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.EventListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.util.Comparator.comparingDouble;
public class VerticalVmCpuScalingDynamicThreshold
{
    private static final int SCHEDULING_INTERVAL = 1;
    private static final int HOSTS = 1;
    private static final int HOST_PES = 32;
    private static final int VMS = 1;
    private static final int VM_PES = 14;
    private static final int VM_RAM = 1200;
    private final CloudSim simulation;
    private DatacenterBroker broker0;
    private List<Host> hostList;
    private List<Vm> vmList;
    private List<Cloudlet> cloudletList;
    private static final int CLOUDLETS = 10;
    private static final int CLOUDLETS_INITIAL_LENGTH = 20_000;
    private int createsVms;

    public static void main(String[] args)
    {
        new VerticalVmCpuScalingDynamicThreshold();
    }
    public VerticalVmCpuScalingDynamicThreshold()
    {
        hostList = new ArrayList<>(HOSTS);
        vmList = new ArrayList<>(VMS);
        cloudletList = new ArrayList<>(CLOUDLETS);
        simulation = new CloudSim();
        simulation.addOnClockTickListener(this::onClockTickListener);
        createDatacenter();
        broker0 = new DatacenterBrokerSimple(simulation);
        vmList.addAll(createListOfScalableVms(VMS));
        createCloudletListsWithDifferentDelays();
        broker0.submitVmList(vmList);
        broker0.submitCloudletList(cloudletList);
        simulation.start();
        printSimulationResults();
    }
    private void onClockTickListener(EventInfo evt)
    {
        vmList.forEach(vm -> {
            System.out.printf(
                "\t\tTime %6.1f: Vm %d CPU Usage: %6.2f%% (%2d vCPUs. Running Cloudlets: #%02d) Upper Threshold: %.2f History Entries: %d%n",
                evt.getTime(), vm.getId(), vm.getCpuPercentUtilization()*100.0,
                vm.getNumberOfPes(),
                vm.getCloudletScheduler().getCloudletExecList().size(),
                vm.getPeVerticalScaling().getUpperThresholdFunction().apply(vm),
                vm.getUtilizationHistory().getHistory().size());
        });
    }
    private void printSimulationResults()
    {
        final List<Cloudlet> finishedCloudlets = broker0.getCloudletFinishedList();
        final Comparator<Cloudlet> sortByVmId = comparingDouble(c -> c.getVm().getId());
        final Comparator<Cloudlet> sortByStartTime = comparingDouble(Cloudlet::getExecStartTime);
        finishedCloudlets.sort(sortByVmId.thenComparing(sortByStartTime));
        for(int i=0;i<finishedCloudlets.size();i++)
        {
        	Cloudlet t=finishedCloudlets.get(i);
        	//System.out.println(t.getActualCpuTime());
        	System.out.println(t.getAccumulatedBwCost());
        }
        //System.out.println(c.getFinishTime());
        new CloudletsTableBuilder(finishedCloudlets).build();
    }
    private void createDatacenter()
    {
        for (int i = 0; i < HOSTS; i++) 
        {
            hostList.add(createHost());
        }
        Datacenter dc0 = new DatacenterSimple(simulation, hostList, new VmAllocationPolicySimple());
        dc0.setSchedulingInterval(SCHEDULING_INTERVAL);
    }
    private Host createHost() 
    {
        List<Pe> peList = new ArrayList<>(HOST_PES);
        for (int i = 0; i < HOST_PES; i++) 
        {
            peList.add(new PeSimple(1000, new PeProvisionerSimple()));
        }
        final long ram = 20000; 
        final long bw = 100000; 
        final long storage = 10000000;
        final int id = hostList.size();
        return new HostSimple(ram, bw, storage, peList).setRamProvisioner(new ResourceProvisionerSimple()).setBwProvisioner(new ResourceProvisionerSimple()).setVmScheduler(new VmSchedulerTimeShared());
    }

    private List<Vm> createListOfScalableVms(final int numberOfVms)
    {
        List<Vm> newList = new ArrayList<>(numberOfVms);
        for (int i = 0; i < numberOfVms; i++)
        {
            Vm vm = createVm();
            vm.setPeVerticalScaling(createVerticalPeScaling());
            newList.add(vm);
        }
        return newList;
    }
    private Vm createVm()
    {
        final int id = createsVms++;
        final Vm vm = new VmSimple(id, 1000, VM_PES).setRam(VM_RAM).setBw(1000).setSize(10000).setCloudletScheduler(new CloudletSchedulerTimeShared());
        vm.getUtilizationHistory().enable();
        return vm;
    }

    private VerticalVmScaling createVerticalPeScaling()
    {
        final double scalingFactor = 0.1;
        VerticalVmScalingSimple verticalCpuScaling = new VerticalVmScalingSimple(Processor.class, scalingFactor);
        verticalCpuScaling.setResourceScaling(new ResourceScalingInstantaneous());
        verticalCpuScaling.setResourceScaling(vs -> 2*vs.getScalingFactor()*vs.getAllocatedResource());
        verticalCpuScaling.setLowerThresholdFunction(this::lowerCpuUtilizationThreshold);
        verticalCpuScaling.setUpperThresholdFunction(this::upperCpuUtilizationThreshold);
        return verticalCpuScaling;
    }
    private double lowerCpuUtilizationThreshold(final Vm vm)
    {
        return 0.1;
    }
    private double upperCpuUtilizationThreshold(final Vm vm)
    {
    	return 0.5;
    }
    private void createCloudletListsWithDifferentDelays()
    {
        final int initialCloudletsNumber = (int)(CLOUDLETS/2.5);
        final int remainingCloudletsNumber = CLOUDLETS-initialCloudletsNumber;
        //Creates a List of Cloudlets that will start running immediately when the simulation starts
        for (int i = 0; i < initialCloudletsNumber; i++)
        {
            cloudletList.add(createCloudlet(CLOUDLETS_INITIAL_LENGTH+(i*1000), 2));
        }
        for (int i = 1; i <= remainingCloudletsNumber; i++)
        {
            cloudletList.add(createCloudlet(CLOUDLETS_INITIAL_LENGTH*2/i, 1,i*2));
        }
    }
    private Cloudlet createCloudlet(final long length, final int numberOfPes)
    {
        return createCloudlet(length, numberOfPes, 0);
    }
    private Cloudlet createCloudlet(final long length, final int numberOfPes, final double delay)
    {
        
        final UtilizationModel utilizationCpu = new UtilizationModelFull();
        final UtilizationModel utilizationModelDynamic = new UtilizationModelDynamic(1.0/CLOUDLETS);
        Cloudlet cl = new CloudletSimple(length, numberOfPes);
        cl.setFileSize(1024).setOutputSize(1024).setUtilizationModelBw(utilizationModelDynamic).setUtilizationModelRam(utilizationModelDynamic).setUtilizationModelCpu(utilizationCpu).setSubmissionDelay(delay);
        return cl;
    }
}
