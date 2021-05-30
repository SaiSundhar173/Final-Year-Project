
package org.cloudsimplus.examples.autoscaling;

import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.util.TraceReaderAbstract;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.builders.tables.TextTableColumn;
import org.cloudsimplus.listeners.HostEventInfo;
import org.cloudsimplus.traces.google.GoogleMachineEventsTraceReader;
import org.cloudsimplus.traces.google.GoogleTaskUsageTraceReader;
import org.cloudsimplus.traces.google.MachineEvent;
import org.cloudsimplus.traces.google.MachineEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GoogleMachineEventsExample1 {
    private static final String TRACE_FILENAME = "workload/google-traces/machine-events-sample-1.csv";
    private static final int HOST_BW = 10;
    private static final long HOST_STORAGE = 100000;
    private static final double HOST_MIPS = 1000;

    private static final int CLOUDLET_LENGTH = 100000;
    private static final int DATACENTERS_NUMBER = 2;
    private List<DatacenterBroker> brokers;
    private final CloudSim simulation;
    private DatacenterBroker broker0;
    private List<Datacenter> datacenters;

    public static void main(String[] args) {
        new GoogleMachineEventsExample1();
    }

    public GoogleMachineEventsExample1() {

        simulation = new CloudSim();
        createDatacenters();

        //Creates a broker that is a software acting on behalf a cloud customer to manage his/her VMs and Cloudlets
        broker0 = new DatacenterBrokerSimple(simulation);

        List<Vm> vmList = createAndSubmitVms(datacenters.get(0));
        readTaskUsageTraceFile();
        createAndSubmitCloudlets(vmList);

        /* Sets a listener method that will be called every time a new Host becomes available during simulation runtime
        * for the second Datacenter*/
        datacenters.get(1).addOnHostAvailableListener(this::onHostAvailableListener);

        simulation.start();

        final List<Cloudlet> finishedCloudlets = broker0.getCloudletFinishedList();
        new CloudletsTableBuilder(finishedCloudlets)
                .addColumn(5, new TextTableColumn("Host Startup", "Time"), this::getHostStartupTime)
                .build();
    }
    private void readTaskUsageTraceFile() {
        final String fileName = "workload/google-traces/task_usage_predicted.csv";
        GoogleTaskUsageTraceReader.getInstances(brokers, fileName);
        
      //System.out.printf("%d Cloudlets processed from the %s trace file.%s", processedCloudlets.size(), fileName, System.lineSeparator());
        //System.out.println();
        final Set<Cloudlet> processedCloudlets = process();
        
    }
   

	private double getHostStartupTime(final Cloudlet cloudlet) {
        return cloudlet.getVm().getHost().getStartTime();
    }

    private void onHostAvailableListener(HostEventInfo info) {
        final Vm vm = createVm(info.getHost());
        broker0.submitVm(vm);
        broker0.submitCloudlet(createCloudlet(vm));
    }

    private void createDatacenters() {
        datacenters = new ArrayList<>(DATACENTERS_NUMBER);

        final GoogleMachineEventsTraceReader reader = GoogleMachineEventsTraceReader.getInstance(TRACE_FILENAME, this::createHost);
        reader.setMaxRamCapacity(32);
        reader.setMaxCpuCores(10);

        //Creates Datacenters with no hosts.
        for(int i = 0; i < DATACENTERS_NUMBER; i++){
            datacenters.add(new DatacenterSimple(simulation, new VmAllocationPolicySimple()));
        }

        reader.setDatacenterForLaterHosts(datacenters.get(1));
        final List<Host> hostList = new ArrayList<>(reader.process());

        System.out.println();
        System.out.printf("# Created %d Hosts that were immediately available from the Google trace file%n", hostList.size());
        System.out.printf("# %d Hosts will be available later on (according to the trace timestamp)%n", reader.getNumberOfLaterAvailableHosts());
        System.out.printf("# %d Hosts will be removed later on (according to the trace timestamp)%n%n", reader.getNumberOfHostsForRemoval());

        //Finally, the immediately created Hosts are added to the first Datacenter
        datacenters.get(0).addHostList(hostList);
    }

    private Host createHost(final MachineEvent event) {
        final Host host = new HostSimple(event.getRam(), HOST_BW, HOST_STORAGE, createPesList(event.getCpuCores()));
        host.setId(event.getMachineId());
        return host;
    }

    private List<Pe> createPesList(final int count) {
        List<Pe> cpuCoresList = new ArrayList<>(count);
        for(int i = 0; i < count; i++){
            cpuCoresList.add(new PeSimple(HOST_MIPS, new PeProvisionerSimple()));
        }

        return cpuCoresList;
    }

    private List<Vm> createAndSubmitVms(Datacenter datacenter) {
        final List<Vm> list = new ArrayList<>(datacenter.getHostList().size());
        /* Creates 1 VM for each available Host of the datacenter.
        *  Each VM will have the same RAM, BW and Storage of the its Host. */
        for (Host host : datacenter.getHostList()) {
            list.add(createVm(host));
        }

        broker0.submitVmList(list);
        return list;
    }
    
    private Vm createVm(final Host host) {
        return new VmSimple(1000, host.getNumberOfPes())
            .setRam(host.getRam().getCapacity()).setBw(HOST_BW).setSize(HOST_STORAGE)
            .setCloudletScheduler(new CloudletSchedulerSpaceShared());
    }

    private void createAndSubmitCloudlets(final List<Vm> vmList) {
        final List<Cloudlet> list = new ArrayList<>(vmList.size());
        for (Vm vm : vmList) {
            Cloudlet cloudlet = createCloudlet(vm);
            list.add(cloudlet);
        }

        broker0.submitCloudletList(list);
    }

    private Cloudlet createCloudlet(Vm vm) {
        UtilizationModel utilization = new UtilizationModelFull();
        return new CloudletSimple(CLOUDLET_LENGTH, vm.getNumberOfPes())
            .setFileSize(1024)
            .setOutputSize(1024)
            .setUtilizationModel(utilization)
            .setVm(vm);
    }
    private Set<Cloudlet> process() {
		return null;
	}
}
