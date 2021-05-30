package org.cloudsimplus.examples.autoscaling;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicyFirstFit;
import org.cloudbus.cloudsim.allocationpolicies.migration.VmAllocationPolicyMigration;
import org.cloudbus.cloudsim.allocationpolicies.migration.VmAllocationPolicyMigrationAbstract;
import org.cloudbus.cloudsim.allocationpolicies.migration.VmAllocationPolicyMigrationBestFitStaticThreshold;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.hosts.HostStateHistoryEntry;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.builders.tables.HostHistoryTableBuilder;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.EventListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
public final class ManualMigrationExample1
{
    private static final int  SCHEDULING_INTERVAL = 1;
    private static final int  HOSTS = 5;
    private static final int  VMS = 3;
    private static final int  HOST_MIPS = 1000; //for each PE
    private static final int  HOST_INITIAL_PES = 4;
    private static final long HOST_RAM = 500000; //host memory (MB)
    private static final long HOST_STORAGE = 1000000; //host storage
    private static final long   HOST_BW = 16000L; //Mb/s
    private static final int    VM_MIPS = 1000; //for each PE
    private static final long   VM_SIZE = 1000; //image size (MB)
    private static final int    VM_RAM = 10000; //VM memory (MB)
    private static final double VM_BW = HOST_BW/(double)VMS;
    private static final int    VM_PES = 2;
    private static final long   CLOUDLET_LENGHT = 20000;
    private static final long   CLOUDLET_FILESIZE = 300;
    private static final long   CLOUDLET_OUTPUTSIZE = 300;

    private final List<Vm> vmList = new ArrayList<>();
    private final DatacenterBrokerSimple broker;
    private final Datacenter datacenter0;

    private CloudSim simulation;
    private List<Host> hostList;
    private boolean migrationRequested;

    public static void main(String[] args)
    {
        new ManualMigrationExample1();
    }
    public ManualMigrationExample1()
    {
        System.out.println("Starting " + getClass().getSimpleName());
        simulation = new CloudSim();

        this.datacenter0 = createDatacenter();
        broker = new DatacenterBrokerSimple(simulation);
        createAndSubmitVms(broker);
        createAndSubmitCloudlets(broker);
        simulation.addOnClockTickListener(this::clockTickListener);

        simulation.start();

        final List<Cloudlet> finishedList = broker.getCloudletFinishedList();
        finishedList.sort(
            Comparator.comparingLong((Cloudlet c) -> c.getVm().getHost().getId())
                      .thenComparingLong(c -> c.getVm().getId()));
        new CloudletsTableBuilder(finishedList).build();
        System.out.printf("%nHosts CPU usage History (when the allocated MIPS is lower than the requested, it is due to VM migration overhead)%n");

        hostList.forEach(this::printHostHistory);
        System.out.println(getClass().getSimpleName() + " finished!");
    }
    private void clockTickListener(EventInfo info)
    {
        if(!migrationRequested && info.getTime() >= 10)
        {
            Vm sourceVm = vmList.get(0);
            Host targetHost = hostList.get(hostList.size() - 1);
            System.out.printf("%n# Requesting the migration of %s to %s%n%n", sourceVm, targetHost);
            datacenter0.requestVmMigration(sourceVm, targetHost);
            this.migrationRequested = true;
        }
    }
    private void printHostHistory(Host host) {
        final boolean cpuUtilizationNotZero =
            host.getStateHistory()
                .stream()
                .map(HostStateHistoryEntry::getPercentUsage)
                .anyMatch(cpuUtilization -> cpuUtilization > 0);

        if(cpuUtilizationNotZero) {
            new HostHistoryTableBuilder(host).setTitle(host.toString()).build();
        } else System.out.printf("\t%s CPU was zero all the time%n", host);
    }
    public void createAndSubmitCloudlets(DatacenterBroker broker) {
        final List<Cloudlet> list = new ArrayList<>(VMS);
        for(Vm vm: vmList){
            list.add(createCloudlet(vm, broker));
        }

        broker.submitCloudletList(list);
    }
    public Cloudlet createCloudlet(Vm vm, DatacenterBroker broker) {
        final Cloudlet cloudlet =
            new CloudletSimple(CLOUDLET_LENGHT, (int)vm.getNumberOfPes())
                .setFileSize(CLOUDLET_FILESIZE)
                .setOutputSize(CLOUDLET_OUTPUTSIZE)
                .setUtilizationModel(new UtilizationModelFull());
        broker.bindCloudletToVm(cloudlet, vm);

        return cloudlet;
    }
    public void createAndSubmitVms(DatacenterBroker broker) {
        final List<Vm> list = new ArrayList<>(VMS);
        for(int i = 0; i < VMS; i++){
            list.add(createVm(VM_PES));
        }

        vmList.addAll(list);
        broker.submitVmList(list);
    }
    public Vm createVm(int pes) {
        final Vm vm = new VmSimple(VM_MIPS, pes);
        vm
          .setRam(VM_RAM).setBw((long)VM_BW).setSize(VM_SIZE)
          .setCloudletScheduler(new CloudletSchedulerTimeShared());
        return vm;
    }
    private Datacenter createDatacenter() {
        this.hostList = new ArrayList<>();
        for(int i = 0; i < HOSTS; i++)
        {
            final int pes = HOST_INITIAL_PES + i;
            hostList.add(createHost(pes, HOST_MIPS));
        }
        System.out.println();

        Datacenter dc = new DatacenterSimple(simulation, hostList, new VmAllocationPolicyFirstFit());
        dc.setSchedulingInterval(SCHEDULING_INTERVAL);
        return dc;
    }
    public Host createHost(int numberOfPes, long mipsByPe)
    {
            List<Pe> peList = createPeList(numberOfPes, mipsByPe);
            Host host = new HostSimple(HOST_RAM, HOST_BW, HOST_STORAGE, peList);
            host.setVmScheduler(new VmSchedulerTimeShared());
            host.enableStateHistory();
            return host;
    }
    public List<Pe> createPeList(int numberOfPEs, long mips)
    {
        final List<Pe> list = new ArrayList<>(numberOfPEs);
        for(int i = 0; i < numberOfPEs; i++) {
            list.add(new PeSimple(mips));
        }
        return list;
    }
}