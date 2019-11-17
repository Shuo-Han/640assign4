package edu.wisc.cs.sdn.apps.l3routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;

public class L3Routing implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener, IDeviceListener {
	// The getSimpleName() method of java Class class returns the simple name of the underlying class as given in the source code. 
	// In the case of an anonymous class, it returns an empty string.
	public static final String MODULE_NAME = L3Routing.class.getSimpleName();
	
	// Interface to the logging system
	private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
	
	// Interface to Floodlight core for interacting with connected switches
	private IFloodlightProviderService floodlightProv;
	
	// Interface to link discovery service
	private ILinkDiscoveryService linkDiscProv;
	
	// Interface to device manager service
	private IDeviceService deviceProv;
	
	// Switch table in which rules should be installed
	public static byte table;
	
	// Map of hosts to devices
	private Map<IDevice, Host> knownHosts;
	
	/**
	 * Loads dependencies and initializes data structures.
	 */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String, String> config = context.getConfigParams(this);
	    table = Byte.parseByte(config.get("table"));
	    
		this.floodlightProv = context.getServiceImpl(IFloodlightProviderService.class);
	    this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
	    this.deviceProv = context.getServiceImpl(IDeviceService.class);
	    
	    this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);
		
		/*********************************************************************/
		/* TODO: Initialize variables or perform startup tasks, if necessary */
		
		/*********************************************************************/
	}
	
    /**
     * Get a list of all known hosts in the network.
     */
    private Collection<Host> getHosts() { 
    	return this.knownHosts.values(); 
    }
	
    /**
     * Get a map of all active switches in the network. Switch DPID is used as
     * the key.
     */
	private Map<Long, IOFSwitch> getSwitches() { 
		return floodlightProv.getAllSwitchMap(); 
	}
	
    /**
     * Get a list of all active links in the network.
     * A link has four variables
     * long src; ("src-switch")
     * int srcPort;("src-port")
     * long dst; ("dst-switch")
     * int dstPort; ("dst-port")
     */
    private Collection<Link> getLinks() { 
    	return linkDiscProv.getLinks().keySet(); 
    }

    /**
     * Event handler called when a host joins the network.
     * @param device information about the host
     */
	@Override
	public void deviceAdded(IDevice device) {
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null) {
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);
			
			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host          */
			
			/*****************************************************************/
			BellmanFord();
		}
	}

	/**
     * Event handler called when a host is no longer attached to a switch.
     * @param device information about the host
     */
	@Override
	public void deviceRemoved(IDevice device) {
		Host host = this.knownHosts.get(device);
		if (null == host) { 
			return; 
		}
		this.knownHosts.remove(device);
		
		log.info(String.format("Host %s is no longer attached to a switch", host.getName()));
		
		/*********************************************************************/
		/* TODO: Update routing: remove rules to route to host               */
		
		/*********************************************************************/
		BellmanFord();
	}

	/**
     * Event handler called when a host moves within the network.
     * @param device information about the host
     */
	@Override
	public void deviceMoved(IDevice device) {
		Host host = this.knownHosts.get(device);
		if (null == host) {
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		if (!host.isAttachedToSwitch()) {
			this.deviceRemoved(device);
			return;
		}
		log.info(String.format("Host %s moved to s%d:%d", host.getName(), host.getSwitch().getId(), host.getPort()));
		
		/*********************************************************************/
		/* TODO: Update routing: change rules to route to host               */
		
		/*********************************************************************/
		BellmanFord();
	}
	
    /**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override		
	public void switchAdded(long switchId) {
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		
		/*********************************************************************/
		BellmanFord();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) {
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		
		/*********************************************************************/
		BellmanFord();
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		for (LDUpdate update : updateList) {
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst()) {
				log.info(String.format("Link s%s:%d -> host updated", 
					update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else {
				log.info(String.format("Link s%s:%d -> s%s:%d updated", 
					update.getSrc(), update.getSrcPort(),
					update.getDst(), update.getDstPort()));
			}
		}
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		
		/*********************************************************************/
		BellmanFord();
	}
	
	public void BellmanFord() {
		// get all the hosts, switches, and links in the network 
		Collection<Host> hosts = getHosts();
		Map<Long, IOFSwitch> switches = getSwitches();
    	Collection<Link> links = getLinks();
		
		Map<IOFSwitch, Integer> weights = new HashMap<IOFSwitch, Integer>(); // cost of each interface of switch?
		Map<IOFSwitch, Integer> ports = new HashMap<IOFSwitch, Integer>(); // post nuumber of each interface of switch?

		// treat each host as a source in BellmanFord, and get the shortest path between source and the other hosts
		// the path is actually computed from the distance between switches connected to the the source and another host
		for (Host host: hosts) {
			// going one switch at a time
			IOFSwitch currSwitch = null;
			// get the switch which the host is connected to
			IOFSwitch source = host.getSwitch();		

			// reset the graph that consists of switches, hosts, and links
			Iterator<Map.Entry<Long, IOFSwitch>> switchIterator = switches.entrySet().iterator();
			while (switchIterator.hasNext()) {
				Map.Entry<Long, IOFSwitch> switchEntry = switchIterator.next();
				currSwitch = switchEntry.getValue();
				
				// at the beginning, the distance between source and source is zero, 
				// while the distance between source and other hosts are infinity.
				weights.put(currSwitch, new Integer(currSwitch.equals(source)? 0:10000));
				// for the source, the port for a packet to go is the port that the host is connected to
				// because we are building the table for that specific host.
				ports.put(currSwitch, new Integer(currSwitch.equals(source)? host.getPort():0));
			}
			
			// find the shortest path
			// the relaxation time is n - 1 when the number of switches is n, and given that we can do BellmanFord in a BFS manner,
			// while we first update the path between source and its nearest neighbors/switches, and then the second nearest neighbors.
			// but we don't know which switches are the nearest ones and so on so forth, so we do n relaxation times.
			Iterator<Map.Entry<Long, IOFSwitch>> switchIter = switches.entrySet().iterator();
			while (switchIter.hasNext()) {	
				for (Link link: links) {
					// update the cost for a switch to reach the source
					if (weights.get(switches.get(link.getSrc())).intValue() + 1 < weights.get(switches.get(link.getDst())).intValue()) {
						weights.put(switches.get(link.getDst()), new Integer(1 + weights.get(switches.get(link.getSrc())).intValue()));
						// if the destination of a packet is the current host, and the packet is in the switch "switches.get(link.getDst())", 
						// then it should go the port "link.getDstPort()".
						ports.put(switches.get(link.getDst()), link.getDstPort()); 
					}
				}
				switchIter.next();
			}

			switchIter = switches.entrySet().iterator();
			IOFSwitch sw;
			
			// Once you have determined the shortest path to reach host h from h’, you must install a rule in the flow table in every switch in the path.
			// In other words, in oder to let a packet know where it should go in a switch, we should update the flow table by updating its flow entry. 
			while (switchIter.hasNext()) {
				Map.Entry<Long, IOFSwitch> switchEntry = switchIter.next();
				sw = switchEntry.getValue();
				
				/** 
				 * Note, in open flow protocol, a switch has a pipeline, which is composed of flow tables, which are composed of flow entries.
				 * Pipeline: the set of linked flow tables that provide matching, forwarding, and packet modifications in an OpenFlow switch.
				 * Flow Table: a stage of the pipeline, contains flow entries.
				 * Flow Entry: an element in a flow table used to match and process packets. It contains a set of match fields for matching packets, 
				 *             a priority for matching precedence, a set of counters to track packets, and a set of instructions to apply.
				 * Match Field: a field against which a packet is matched, including packet headers, the ingress port, and the metadata value.
				 * Instruction: instructions are attached to a flow entry and describe the OpenFlow processing that happen when a packet matches the flow entry.
				 *              an instruction either modifies pipeline processing, such as direct the packet to another flow table, or contains a set of actions to add to the action set, or contains a list of actions to apply immediately to the packet.
				 * Action: an operation that forwards the packet to a port or modifies the packet, such as decrementing the TTL field. 
				 * 		   actions may be specified as part of the instruction set associated with a flow entry, or in an action bucket associated with a group entry. 
				 *         actions may be accumulated in the Action Set of the packet or applied immediately to the packet.
				 * Action Set: a set of actions associated with the packet that are accumulated while the packet is processed by each table and that are executed when the instruction set instructs the packet to exit the processing pipeline.
				 */
	
				// In order to update a flow entry, we need to set the rule of its Match Field. 
				// The rule should match IP packets (i.e., Ethernet type is IPv4) whose destination IP is the IP address assigned to host h. 
				// You can specify this in Floodlight by creating a new OFMatch object and calling the set methods for the appropriate fields. 
				OFMatch rule = new OFMatch();
				rule.setDataLayerType((short) 0x800);
				rule.setNetworkDestination(OFMatch.ETH_TYPE_IPV4, host.getIPv4Address().intValue());
				
				// a flow entry also has an instruction attached to it, so we need to update the instruction.
				// an instruction is composed of actions, and actions can be divided into different types, like "applying to packet" or "modifying pipeline".
				// therefore, we first create an action, and then decide its type, and then add it to the instruction.
				// in other words, the rule’s action should be to output packets on the appropriate port in order to reach the next switch in the path. 
				// you can specify this in Floodlight by creating an OFInstructionApplyActions object whose set of actions consists of a single OFActionOutput object with the appropriate port number.
				OFActionOutput action = new OFActionOutput(!sw.equals(host.getSwitch())? ports.get(sw).intValue():host.getPort());
				List<OFAction> actions = new ArrayList<OFAction>();
				actions.add(action);
				
				OFInstructionApplyActions instruct = new OFInstructionApplyActions(actions);
				List<OFInstruction> instructions = new ArrayList<OFInstruction>();
				instructions.add(instruct);
				
				// install rules in the table specified in the table class variable in the L3Routing class, this table is a switch's flow table.
				// rules should never timeout and have a default priority (both defined as constants in the SwitchCommands class).
				SwitchCommands.installRule(sw, table, SwitchCommands.DEFAULT_PRIORITY, rule, instructions);
			}
		}
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) 
	{ this.linkDiscoveryUpdate(Arrays.asList(update)); }
	
	/**
     * Event handler called when the IP address of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceIPV4AddrChanged(IDevice device) 
	{ this.deviceAdded(device); }

	/**
     * Event handler called when the VLAN of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceVlanChanged(IDevice device) 
	{ /* Nothing we need to do, since we're not using VLANs */ }
	
	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId) 
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since we'll get a linkDiscoveryUpdate event */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) 
	{ return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) 
	{ return false; }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(ILinkDiscoveryService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}
}