/*
 * ArgonMS MapleStory server emulator written in Java
 * Copyright (C) 2011  GoldenKevin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package argonms.game.net;

import argonms.common.net.external.ClientListener;
import argonms.common.net.external.ClientListener.ClientFactory;
import argonms.common.net.external.ClientSendOps;
import argonms.common.net.external.PlayerLog;
import argonms.common.net.internal.RemoteCenterOps;
import argonms.common.util.Scheduler;
import argonms.common.util.collections.Pair;
import argonms.common.util.output.LittleEndianByteArrayWriter;
import argonms.game.GameServer;
import argonms.game.character.GameCharacter;
import argonms.game.character.PlayerContinuation;
import argonms.game.field.GameMap;
import argonms.game.field.MapFactory;
import argonms.game.net.external.ClientGamePacketProcessor;
import argonms.game.net.external.GameClient;
import argonms.game.net.external.GamePackets;
import argonms.game.net.internal.InterChannelCommunication;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author GoldenKevin
 */
public class WorldChannel {
	private static final Logger LOG = Logger.getLogger(WorldChannel.class.getName());
	private static final int CHANNEL_CHANGE_TIMEOUT = 5000;

	private final Map<Integer, PlayerContinuation> channelChangeData;
	private final Map<Integer, Pair<Byte, ScheduledFuture<?>>> queuedChannelChanges;
	private long startTime;
	private final ClientListener<GameClient> handler;
	private final byte world, channel;
	private int port;
	private final MapFactory mapFactory;
	private final PlayerLog<GameCharacter> storage;
	private InterChannelCommunication worldComm;

	public WorldChannel(final byte world, final byte channel, int port) {
		channelChangeData = new ConcurrentHashMap<Integer, PlayerContinuation>();
		queuedChannelChanges = new ConcurrentHashMap<Integer, Pair<Byte, ScheduledFuture<?>>>();
		this.world = world;
		this.channel = channel;
		this.port = port;
		mapFactory = new MapFactory();
		storage = new PlayerLog<GameCharacter>();
		handler = new ClientListener<GameClient>(new ClientGamePacketProcessor(), new ClientFactory<GameClient>() {
			@Override
			public GameClient newInstance() {
				return new GameClient(world, channel);
			}
		});
	}

	public void listen(boolean useNio) {
		if (handler.bind(port)) {
			LOG.log(Level.INFO, "World {0} Channel {1} is online.", new Object[] { world, channel });
		} else {
			shutdown();
			return;
		}
		startTime = System.currentTimeMillis();
		Scheduler.getInstance().runRepeatedly(new Runnable() {
			@Override
			public void run() {
				for (GameMap map : mapFactory.getMaps().values())
					map.respawnMobs();
			}
		}, 0, 10000);
	}

	public byte getChannelId() {
		return channel;
	}

	public long getTimeStarted() {
		return startTime;
	}

	public void addPlayer(GameCharacter p) {
		storage.addPlayer(p);
		sendNewLoad(storage.getConnectedCount());
	}

	public void removePlayer(GameCharacter p) {
		storage.deletePlayer(p);
		sendNewLoad(storage.getConnectedCount());
	}

	public GameCharacter getPlayerById(int characterid) {
		return storage.getPlayer(characterid);
	}

	public GameCharacter getPlayerByName(String name) {
		return storage.getPlayer(name);
	}

	public boolean isPlayerConnected(int characterid) {
		return storage.getPlayer(characterid) != null;
	}

	public Collection<GameCharacter> getConnectedPlayers() {
		return storage.getConnectedPlayers();
	}

	private void channelChangeError(GameCharacter p) {
		//TODO: IMPLEMENT/SHOW ERROR MESSAGE
		queuedChannelChanges.remove(Integer.valueOf(p.getId()));
		p.getClient().getSession().send(GamePackets.writeEnableActions());
	}

	public void requestChannelChange(final GameCharacter p, byte destCh) {
		queuedChannelChanges.put(Integer.valueOf(p.getId()), new Pair<Byte, ScheduledFuture<?>>(Byte.valueOf(destCh), Scheduler.getInstance().runAfterDelay(new Runnable() {
			@Override
			public void run() {
				channelChangeError(p);
			}
		}, CHANNEL_CHANGE_TIMEOUT)));
		worldComm.sendChannelChangeRequest(destCh, p);
	}

	public void performChannelChange(int playerId) {
		Pair<Byte, ScheduledFuture<?>> channelChangeState = queuedChannelChanges.remove(Integer.valueOf(playerId));
		channelChangeState.right.cancel(false);
		byte[] destHost;
		int destPort;
		try {
			Pair<byte[], Integer> hostAndPort = worldComm.getChannelHost(channelChangeState.left.byteValue());
			destHost = hostAndPort.left;
			destPort = hostAndPort.right.intValue();
		} catch (UnknownHostException e) {
			destHost = null;
			destPort = -1;
		}
		GameCharacter p = storage.getPlayer(playerId);
		if (destHost != null && destPort != -1) {
			p.prepareChannelChange();
			p.getClient().getSession().send(writeNewGameHost(destHost, destPort));
		} else {
			channelChangeError(p);
		}
	}

	public void storePlayerBuffs(int playerId, PlayerContinuation context) {
		channelChangeData.put(Integer.valueOf(playerId), context);
	}

	public boolean applyBuffsFromLastChannel(GameCharacter p) {
		PlayerContinuation context = channelChangeData.remove(Integer.valueOf(p.getId()));
		if (context == null)
			return false;
		context.applyTo(p);
		return true;
	}

	private void sendNewLoad(short now) {
		LittleEndianByteArrayWriter lew = new LittleEndianByteArrayWriter(3);
		lew.writeByte(RemoteCenterOps.POPULATION_CHANGED);
		lew.writeByte(channel);
		lew.writeShort(now);
		GameServer.getInstance().getCenterInterface().getSession().send(lew.getBytes());
	}

	public void startup(int port) {
		if (port == -1) {
			this.port = port;
			if (handler.bind(port)) {
				LOG.log(Level.INFO, "Channel {0} is online.", channel);
				sendNewPort();
			}
		}
	}

	public void shutdown() {
		port = -1;
		sendNewPort();
	}

	private void sendNewPort() {
		LittleEndianByteArrayWriter lew = new LittleEndianByteArrayWriter(4);
		lew.writeByte(RemoteCenterOps.MODIFY_CHANNEL_PORT);
		lew.writeByte(world);
		lew.writeByte(channel);
		lew.writeInt(port);
		GameServer.getInstance().getCenterInterface().getSession().send(lew.getBytes());
	}

	public int getPort() {
		return port;
	}

	public MapFactory getMapFactory() {
		return mapFactory;
	}

	public void createWorldComm(byte[] local) {
		worldComm = new InterChannelCommunication(local, this);
	}

	public InterChannelCommunication getInterChannelInterface() {
		return worldComm;
	}

	private static byte[] writeNewGameHost(byte[] host, int port) {
		LittleEndianByteArrayWriter lew = new LittleEndianByteArrayWriter(9);
		lew.writeShort(ClientSendOps.GAME_HOST_ADDRESS);
		lew.writeBool(true);
		lew.writeBytes(host);
		lew.writeShort((short) port);
		return lew.getBytes();
	}
}