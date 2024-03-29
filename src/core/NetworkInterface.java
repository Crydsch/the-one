/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package core;

import interfaces.ConnectivityGrid;
import interfaces.ConnectivityOptimizer;

import java.util.*;
import java.util.stream.Collectors;

import routing.util.EnergyModel;

import util.ActivenessHandler;

/**
 * Network interface of a DTNHost. Takes care of connectivity among hosts.
 */
abstract public class NetworkInterface implements ModuleCommunicationListener, Comparable<NetworkInterface> {
	/** transmit range -setting id ({@value})*/
	public static final String TRANSMIT_RANGE_S = "transmitRange";
	/** transmit speed -setting id ({@value})*/
	public static final String TRANSMIT_SPEED_S = "transmitSpeed";
	/** scanning interval -setting id ({@value})*/
	public static final String SCAN_INTERVAL_S = "scanInterval";

	/**
	 * Sub-namespace for the network related settings in the Group namespace
	 * ({@value})
	 */
	public static final String NET_SUB_NS = "net";

	/** Activeness offset jitter -setting id ({@value})
	 * The maximum amount of random offset for the offset */
	public static final String ACT_JITTER_S = "activenessOffsetJitter";

	/** {@link ModuleCommunicationBus} identifier for the "scanning interval"
    variable. */
	public static final String SCAN_INTERVAL_ID = "Network.scanInterval";
	/** {@link ModuleCommunicationBus} identifier for the "radio range"
	variable. Value type: double */
	public static final String RANGE_ID = "Network.radioRange";
	/** {@link ModuleCommunicationBus} identifier for the "transmission speed"
    variable. Value type: integer */
	public static final String SPEED_ID = "Network.speed";

	private static final int CON_UP = 1;
	private static final int CON_DOWN = 2;

	private static Random rng;
	protected DTNHost host = null;

	protected String interfacetype;
	protected List<Connection> connections; // connected hosts
	private List<ConnectionListener> cListeners = null; // list of listeners
	private int address; // network interface address
	protected double transmitRange;
	protected double oldTransmitRange;
	protected int transmitSpeed;
	/** scanning interval, or 0.0 if n/a */
	private double scanInterval;
	private double lastScanTime;

	/** activeness handler for the node group */
	private ActivenessHandler ah;
	/** maximum activeness jitter value for the node group */
	private int activenessJitterMax;
	/** this interface's activeness jitter value */
	private int activenessJitterValue;

	static {
		DTNSim.registerForReset(NetworkInterface.class.getCanonicalName());
		reset();
	}

	/**
	 * Resets the static fields of the class
	 */
	public static void reset() {
		rng = new Random(0);
	}

	/**
	 * For creating an empty class of a specific type
	 */
	public NetworkInterface(Settings s) {
		this.interfacetype = s.getNameSpace();
		this.connections = new ArrayList<Connection>();

		this.transmitRange = s.getDouble(TRANSMIT_RANGE_S);
		this.transmitSpeed = s.getInt(TRANSMIT_SPEED_S);
		ensurePositiveValue(transmitRange, TRANSMIT_RANGE_S);
		ensurePositiveValue(transmitSpeed, TRANSMIT_SPEED_S);
	}

	/**
	 * For creating an empty class of a specific type
	 */
	public NetworkInterface() {
		this.interfacetype = "Default";
		this.connections = new ArrayList<Connection>();
	}

	/**
	 * copy constructor
	 */
	public NetworkInterface(NetworkInterface ni) {
		this.connections = new ArrayList<Connection>();
		this.host = ni.host;
		this.cListeners = ni.cListeners;
		this.interfacetype = ni.interfacetype;
		this.transmitRange = ni.transmitRange;
		this.transmitSpeed = ni.transmitSpeed;
		this.scanInterval = ni.scanInterval;
		this.ah = ni.ah;

		if (ni.activenessJitterMax > 0) {
			this.activenessJitterValue = rng.nextInt(ni.activenessJitterMax);
		} else {
			this.activenessJitterValue = 0;
		}

		this.scanInterval = ni.scanInterval;
		/* draw lastScanTime of [0 -- scanInterval] */
		this.lastScanTime = rng.nextDouble() * this.scanInterval;
	}

	/**
	 * Replication function
	 */
	abstract public NetworkInterface replicate();

	/**
	 * For setting the host - needed when a prototype is copied for several
	 * hosts
	 * @param host The host where the network interface is
	 */
	public void setHost(DTNHost host) {
		this.host = host;
		ModuleCommunicationBus comBus = host.getComBus();

		if (!comBus.containsProperty(SCAN_INTERVAL_ID) &&
		    !comBus.containsProperty(RANGE_ID)) {
			/* add properties and subscriptions only for the 1st interface */
			/* TODO: support for multiple interfaces */
			comBus.addProperty(SCAN_INTERVAL_ID, this.scanInterval);
			comBus.addProperty(RANGE_ID, this.transmitRange);
			comBus.addProperty(SPEED_ID, this.transmitSpeed);
			comBus.subscribe(SCAN_INTERVAL_ID, this);
			comBus.subscribe(RANGE_ID, this);
			comBus.subscribe(SPEED_ID, this);
		}
	}

	/**
	 * Sets group-based settings for the network interface
	 * @param s The settings object using the right group namespace
	 */
	public void setGroupSettings(Settings s) {
		s.setSubNameSpace(NET_SUB_NS);
		ah = new ActivenessHandler(s);

		if (s.contains(SCAN_INTERVAL_S)) {
			this.scanInterval =  s.getDouble(SCAN_INTERVAL_S);
		} else {
			this.scanInterval = 0;
		}
		if (s.contains(ACT_JITTER_S)) {
			this.activenessJitterMax = s.getInt(ACT_JITTER_S);
		}

		s.restoreSubNameSpace();
	}

	/**
	 * For checking what interface type this interface is
	 */
	public String getInterfaceType() {
		return interfacetype;
	}

	/**
	 * For setting the connectionListeners
	 * @param cListeners List of connection listeners
	 */
	public void setClisteners(List<ConnectionListener> cListeners) {
		this.cListeners = cListeners;
	}

	/**
	 * Returns the transmit range of this network layer
	 * @return the transmit range
	 */
	public double getTransmitRange() {
		return this.transmitRange;
	}

	/**
	 * Returns the transmit speed of this network layer with respect to the
	 * another network interface
	 * @param ni The other network interface
	 * @return the transmit speed
	 */
	public int getTransmitSpeed(NetworkInterface ni) {
		return this.transmitSpeed;
	}

	/**
	 * Returns a list of currently connected connections
	 * @return a list of currently connected connections
	 */
	public List<Connection> getConnections() {
		return this.connections;
	}

	/**
	 * Returns true if the interface is on at the moment (false if not)
	 * @return true if the interface is on at the moment (false if not)
	 */
	public boolean isActive() {
		boolean active;

		if (ah == null) {
			return true; /* no handler: always active */
		}

		active = ah.isActive(this.activenessJitterValue);

		if (active && host.getComBus().getDouble(EnergyModel.ENERGY_VALUE_ID,
					1) <= 0) {
			/* TODO: better way to check battery level */
			/* no battery -> inactive */
			active = false;
		}

		if (active == false && this.transmitRange > 0) {
			/* not active -> make range 0 */
			this.oldTransmitRange = this.transmitRange;
			host.getComBus().updateProperty(RANGE_ID, 0.0);
		} else if (active == true && this.transmitRange == 0.0) {
			/* active, but range == 0 -> restore range  */
			host.getComBus().updateProperty(RANGE_ID,
					this.oldTransmitRange);
		}
		return active;
	}

	/**
	 * Checks if this interface is currently in the scanning mode
	 * @return True if the interface is scanning; false if not
	 */
	public boolean isScanning() {
		double simTime = SimClock.getTime();

		if (!isActive()) {
			return false;
		}

		if (scanInterval > 0.0) {
			if (simTime < lastScanTime) {
				return false; /* not time for the first scan */
			}
			else if (simTime > lastScanTime + scanInterval) {
				lastScanTime = simTime; /* time to start the next scan round */
				return true;
			}
			else if (simTime != lastScanTime ){
				return false; /* not in the scan round */
			}
		}
		/* interval == 0 or still in the same scan round as when
		   last time asked */
		return true;
	}

	/**
	 * Returns true if one of the connections of this interface is transferring
	 * data
	 * @return true if the interface transferring
	 */
	public boolean isTransferring() {
		for (Connection c : this.connections) {
			if (c.isTransferring()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Connects the interface to another interface.
	 *
	 * Overload this in a derived class.  Check the requirements for
	 * the connection to work in the derived class, then call
	 * connect(Connection, NetworkInterface) for the actual connection.
	 * @param anotherInterface The interface to connect to
	 */
	public abstract void connect(NetworkInterface anotherInterface);

	/**
	 * Connects this host to another host. The derived class should check
	 * that all pre-requisites for making a connection are satisfied before
	 * actually connecting.
	 * @param con The new connection object
	 * @param anotherInterface The interface to connect to
	 */
	protected void connect(Connection con, NetworkInterface anotherInterface) {
		this.connections.add(con);
		notifyConnectionListeners(CON_UP, anotherInterface.getHost());

		// set up bidirectional connection
		anotherInterface.getConnections().add(con);

		// inform routers about the connection
		this.host.connectionUp(con);
		anotherInterface.getHost().connectionUp(con);
	}

	/**
	 * Disconnect a connection between this and another host.
	 * @param other The other host's network interface to disconnect
	 * from this host
	 */
	public void disconnect(NetworkInterface other) {
		for (int i = 0; i < this.connections.size(); i++) {
			Connection con = this.connections.get(i);

			if (con.getOtherInterface(this) == other) {
				disconnect(con, other);
				connections.remove(i);
				return;
			}
		}
		// the connection didn't exist, do nothing
	}

	/**
	 * Disconnects this host from another host.  The derived class should
	 * make the decision whether to disconnect or not
	 * @param con The connection to tear down
	 */
	protected void disconnect(Connection con, NetworkInterface other) {
		// all connections should be up at this stage
		assert con.isUp() : "Connection " + con + " was down!";

		con.setUpState(false);
		notifyConnectionListeners(CON_DOWN, other.getHost());

		// tear down bidirectional connection
		if (!other.getConnections().remove(con)) {
			throw new SimError("No connection " + con + " found in " +
					other);
		}

		this.host.connectionDown(con);
		other.getHost().connectionDown(con);
	}

	/**
	 * Returns true if the given NetworkInterface is connected to this interface.
	 * @param other The other NetworkInterface to check
	 * @return True if the two hosts are connected
	 */
	public boolean isConnected(NetworkInterface other) {
		for (int i = 0; i < this.connections.size(); i++) {
			if (this.connections.get(i).getOtherInterface(this) == other) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Makes sure that a value is positive
	 * @param value Value to check
	 * @param settingName Name of the setting (for error's message)
	 * @throws SettingsError if the value was not positive
	 */
	protected void ensurePositiveValue(double value, String settingName) {
		if (value < 0) {
			throw new SettingsError("Negative value (" + value +
					") not accepted for setting " + settingName);
		}
	}

	/**
	 * Called when <other> interface enters the transmission range
	 * May be called only once even if connection is not immediately established
	 *  Callee is responsible to keep track of available connections
	 * Caller guarantees that:
	 *  - <other> =/= <this>
	 *  - There is no connection yet
	 * @param other The other interface
	 */
	public void linkUp(NetworkInterface other) {
		connect(other);
	}

	/**
	 * Called when <other> interface leaves the transmission range
	 * Caller guarantees that:
	 *  - <other> =/= <this>
	 *  - There was a connection
	 * @param other The other interface
	 */
	public void linkDown(NetworkInterface other) {
		disconnect(other);
	}

	/**
	 * Updates the state of current connections(ie recalculate transmission speeds etc.).
	 */
	public abstract void update();

	/**
	 * Notifies all the connection listeners about a change in connections.
	 * @param type Type of the change (e.g. {@link #CON_DOWN} )
	 * @param otherHost The other host on the other end of the connection.
	 */
	private void notifyConnectionListeners(int type, DTNHost otherHost) {
		if (this.cListeners == null) {
			return;
		}
		for (ConnectionListener cl : this.cListeners) {
			switch (type) {
			case CON_UP:
				cl.hostsConnected(this.host, otherHost);
				break;
			case CON_DOWN:
				cl.hostsDisconnected(this.host, otherHost);
				break;
			default:
				assert false : type;	// invalid type code
			}
		}
	}

	/**
	 * This method is called by the {@link ModuleCommunicationBus} when/if
	 * someone changes the scanning interval, transmit speed, or range
	 * @param key Identifier of the changed value
	 * @param newValue New value for the variable
	 */
	public void moduleValueChanged(String key, Object newValue) {
		if (key.equals(SCAN_INTERVAL_ID)) {
			this.scanInterval = (Double)newValue;
		}
		else if (key.equals(SPEED_ID)) {
			this.transmitSpeed = (Integer)newValue;
		}
		else if (key.equals(RANGE_ID)) {
			this.transmitRange = (Double)newValue;
		}
		else {
			throw new SimError("Unexpected combus ID " + key);
		}
	}

	/**
	 * Creates a connection to another host. This method does not do any checks
	 * on whether the other node is in range or active
	 * (cf. {@link #connect(NetworkInterface)}).
	 * @param anotherInterface The interface to create the connection to
	 */
	public abstract void createConnection(NetworkInterface anotherInterface);

	/**
	 * Returns the DTNHost of this interface
	 */
	public DTNHost getHost() {
		return host;
	}

	/**
	 * Returns the current location of the host of this interface.
	 * @return The location
	 */
	public Coord getLocation() {
		return host.getLocation();
	}

	/**
	 * Returns a string representation of the object.
	 * @return a string representation of the object.
	 */
	public String toString() {
		return this.address + " of " + this.host +
			". Connections: " +	this.connections;
	}

	@Override
	public int compareTo(NetworkInterface b) {
		return this.getHost().getID() - b.getHost().getID();
	}

}
