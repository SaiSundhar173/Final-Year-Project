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
import org.cloudbus.cloudsim.distributions.ContinuousDistribution;
import org.cloudbus.cloudsim.distributions.UniformDistr;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.ResourceProvisionerSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.autoscaling.HorizontalVmScaling;
import org.cloudsimplus.autoscaling.HorizontalVmScalingSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.EventListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Comparator.comparingDouble;
public class LoadBalancerByHorizontalVmScalingExample 
{
    private static final int SCHEDULING_INTERVAL = 5;
    private static final int CLOUDLETS_CREATION_INTERVAL = SCHEDULING_INTERVAL * 2;
    private static final int HOSTS = 50;
    private static final int HOST_PES = 32;
    private static final int VMS = 4;
    private static final int CLOUDLETS = 6;
    private final CloudSim simulation;
    private DatacenterBroker broker0;
    private List<Host> hostList;
    private List<Vm> vmList;
    private List<Cloudlet> cloudletList;
    private static final long[] CLOUDLET_LENGTHS = {2000, 4000, 10000, 16000, 2000, 30000, 20000};
    private ContinuousDistribution rand;
    private int createdCloudlets;
    private int createsVms;
    public static void main(String[] args) 
    {
        new LoadBalancerByHorizontalVmScalingExample();
    }
    public LoadBalancerByHorizontalVmScalingExample() 
    {   
        final long seed = 1;
        rand = new UniformDistr(0, CLOUDLET_LENGTHS.length, seed);
        hostList = new ArrayList<>(HOSTS);
        vmList = new ArrayList<>(VMS);
        cloudletList = new ArrayList<>(CLOUDLETS);

        simulation = new CloudSim();
        simulation.addOnClockTickListener(this::createNewCloudlets);

        createDatacenter();
        broker0 = new DatacenterBrokerSimple(simulation);
        broker0.setVmDestructionDelayFunction(vm -> 10.0);

        vmList.addAll(createListOfScalableVms(VMS));

        createCloudletList();
        broker0.submitVmList(vmList);
        broker0.submitCloudletList(cloudletList);

        simulation.start();

        printSimulationResults();
    }
    private void printSimulationResults() 
    {
        List<Cloudlet> finishedCloudlets = broker0.getCloudletFinishedList();
        Comparator<Cloudlet> sortByVmId = comparingDouble(c -> c.getVm().getId());
        Comparator<Cloudlet> sortByStartTime = comparingDouble(c -> c.getExecStartTime());
        finishedCloudlets.sort(sortByVmId.thenComparing(sortByStartTime));
        new CloudletsTableBuilder(finishedCloudlets).build();
    }
    private void createCloudletList() 
    {
        for (int i = 0; i < CLOUDLETS; i++) 
        {
            cloudletList.add(createCloudlet());
        }
    }
    private void createNewCloudlets(EventInfo eventInfo) 
    {
        final long time = (long) eventInfo.getTime();
        if (time==10) 
        {
            final int numberOfCloudlets = 4;
            System.out.printf("\t#Creating %d Cloudlets at time %d.%n", numberOfCloudlets, time);
            List<Cloudlet> newCloudlets = new ArrayList<>(numberOfCloudlets);
            for (int i = 0; i < numberOfCloudlets; i++) 
            {
                Cloudlet cloudlet = createCloudlet();
                cloudletList.add(cloudlet);
                newCloudlets.add(cloudlet);
            }
            broker0.submitCloudletList(newCloudlets);
        }
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
        final long ram = 2048; // in Megabytes
        final long storage = 1000000; // in Megabytes
        final long bw = 10000; //in Megabits/s
        return new HostSimple(ram, bw, storage, peList).setRamProvisioner(new ResourceProvisionerSimple()).setBwProvisioner(new ResourceProvisionerSimple()).setVmScheduler(new VmSchedulerTimeShared());
    }
    private List<Vm> createListOfScalableVms(final int numberOfVms) 
    {
        List<Vm> newList = new ArrayList<>(numberOfVms);
        for (int i = 0; i < numberOfVms; i++) 
        {
            Vm vm = createVm();
            createHorizontalVmScaling(vm);
            newList.add(vm);
        }
        return newList;
    }
    private void createHorizontalVmScaling(Vm vm) 
    {
        HorizontalVmScaling horizontalScaling = new HorizontalVmScalingSimple();
        horizontalScaling.setVmSupplier(this::createVm).setOverloadPredicate(this::isVmOverloaded);
        vm.setHorizontalScaling(horizontalScaling);
    }
    private boolean isVmOverloaded(Vm vm) 
    {
        return vm.getCpuPercentUtilization() > 0.7;
    }
    private Vm createVm() 
    {
        final int id = createsVms++;
        return new VmSimple(id, 1000, 2).setRam(512).setBw(1000).setSize(10000).setCloudletScheduler(new CloudletSchedulerTimeShared());
    }
    private Cloudlet createCloudlet() 
    {
        final int id = createdCloudlets++;
        //randomly selects a length for the cloudlet
        final long length = CLOUDLET_LENGTHS[(int) rand.sample()];
        UtilizationModel utilization = new UtilizationModelFull();
        return new CloudletSimple(id, length, 2).setFileSize(1024).setOutputSize(1024).setUtilizationModel(utilization);
    }
}