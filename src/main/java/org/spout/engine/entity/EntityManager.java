/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011-2012, SpoutDev <http://www.spout.org/>
 * Spout is licensed under the SpoutDev License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the SpoutDev License Version 1.
 *
 * Spout is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the SpoutDev License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://www.spout.org/SpoutDevLicenseV1.txt> for the full license,
 * including the MIT license.
 */
package org.spout.engine.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.spout.api.datatable.GenericDatatableMap;
import org.spout.api.entity.Controller;
import org.spout.api.entity.Entity;
import org.spout.api.entity.Player;
import org.spout.api.entity.controller.BlockController;
import org.spout.api.entity.controller.PlayerController;
import org.spout.api.math.Vector3;
import org.spout.api.protocol.NetworkSynchronizer;
import org.spout.api.util.StringMap;

import org.spout.engine.util.thread.snapshotable.SnapshotManager;
import org.spout.engine.util.thread.snapshotable.SnapshotableArrayList;
import org.spout.engine.util.thread.snapshotable.SnapshotableHashMap;
import org.spout.engine.world.SpoutChunk;
import org.spout.engine.world.SpoutRegion;

/**
 * A class which manages all of the entities within a world.
 */
public class EntityManager {
	/**
	 * The snapshot manager
	 */
	private final SnapshotManager snapshotManager = new SnapshotManager();
	/**
	 * A map of all the entity ids to the corresponding entities.
	 */
	private final SnapshotableHashMap<Integer, SpoutEntity> entities = new SnapshotableHashMap<Integer, SpoutEntity>(snapshotManager);
	/**
	 * A map of entity types to a set containing all entities of that type.
	 */
	private final ConcurrentHashMap<Class<? extends Controller>, SnapshotableArrayList<SpoutEntity>> groupedEntities = new ConcurrentHashMap<Class<? extends Controller>, SnapshotableArrayList<SpoutEntity>>();
	/**
	 * A list containing all the players in this entity manager.
	 */
	private final SnapshotableArrayList<SpoutPlayer> players = new SnapshotableArrayList<SpoutPlayer>(snapshotManager);
	/**
	 * The next id to check.
	 */
	private final static AtomicInteger nextId = new AtomicInteger(1);
	/**
	 * The String map
	 */
	private final StringMap entityMap = GenericDatatableMap.getStringMap();
	/**
	 * The map of entities to Vector3s(BlockControllers)
	 */
	private final Map<Vector3, Entity> blockEntities = new HashMap<Vector3, Entity>();

	/**
	 * Gets all entities with the specified type.
	 *
	 * @param type The {@link Class} for the type.
	 * @return A set of entities with the specified type.
	 */
	public List<SpoutEntity> getAll(Class<? extends Controller> type) {
		SnapshotableArrayList<SpoutEntity> entities = groupedEntities.get(type);
		if (entities == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(entities.get());
	}

	/**
	 * Gets all entities.
	 *
	 * @return A set of entities.
	 */
	public List<SpoutEntity> getAll() {
		Collection<SpoutEntity> all = entities.get().values();
		if (all == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<SpoutEntity>(all));
	}

	public List<SpoutEntity> getAllLive() {
		Collection<SpoutEntity> all = entities.getLive().values();
		if (all == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<SpoutEntity>(all));
	}

	/**
	 * Gets an entity by its id.
	 *
	 * @param id The id.
	 * @return The entity, or {@code null} if it could not be found.
	 */
	public SpoutEntity getEntity(int id) {
		return entities.get().get(id);
	}

	/**
	 * Allocates the id for an entity.
	 *
	 * @param entity The entity.
	 * @return The id.
	 */
	public int allocate(SpoutEntity entity) {
		return allocate(entity, null);
	}

	/**
	 * Allocates the id for an entity.
	 *
	 * @param entity The entity.
	 * @return The id.
	 */
	public int allocate(SpoutEntity entity, SpoutRegion region) {
		int currentId = entity.getId();
		if (currentId == SpoutEntity.NOTSPAWNEDID) {
			currentId = nextId.getAndIncrement();
			if (currentId == -2) {
				throw new IllegalStateException("No new entity ids left");
			}
			entity.setId(currentId);
		}
		entities.put(currentId, entity);
		entity.setOwningThread(region.getExceutionThread());
		return currentId;
	}

	/**
	 * Deallocates the id for an entity.
	 *
	 * @param entity The entity.
	 */
	public void deallocate(SpoutEntity entity) {
		entities.remove(entity.getId());
		SpoutChunk chunkLive = (SpoutChunk) entity.getChunkLive();
		if (chunkLive != null && chunkLive.isLoaded()) {
			chunkLive.removeEntity(entity);
		}
		SpoutChunk chunk = (SpoutChunk) entity.getChunk();
		if (chunk != null && chunk.isLoaded()) {
			chunk.removeEntity(entity);
		}
		//Players are never removed (offline concept), instead set their ID back to -1 to be reallocated.
		if (entity instanceof Player) {
			entity.setId(SpoutEntity.NOTSPAWNEDID);
		}
	}

	public void addEntity(SpoutEntity entity, SpoutRegion region) {
		allocate(entity, region);
		Controller c = entity.getController();
		if (c != null) {
			if (entity instanceof Player) {
				players.add((SpoutPlayer) entity);
			} else if (c instanceof BlockController) {
				Vector3 pos = entity.getPosition().floor();
				Entity old = blockEntities.put(pos, entity);
				if (old != null) {
					old.kill();
				}
			}
		}
	}

	public boolean isSpawnable(SpoutEntity entity) {
		if (entity.getId() == SpoutEntity.NOTSPAWNEDID) {
			return true;
		}
		return false;
	}

	public void removeEntity(SpoutEntity entity) {
		deallocate(entity);
		Controller c = entity.getController();
		if (c != null) {
			if (entity instanceof Player) {
				players.remove((SpoutPlayer) entity);
			} else if (c instanceof BlockController) {
				Vector3 pos = entity.getPosition().floor();
				Entity be = blockEntities.get(pos);
				if (be == entity) {
					blockEntities.remove(pos);
				}
			}
		}
	}

	/**
	 * Gets all of the player controllers managed this entity manager
	 *
	 * @return players managed by this entity manager
	 */
	public List<Player> getPlayers() {
		List<SpoutPlayer> thePlayers = players.get();
		if (thePlayers == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<Player>(thePlayers));
	}

	public void finalizeRun() {
		for (SpoutEntity e : entities.get().values()) {
			if (e.isDead()) {
				removeEntity(e);
				continue;
			}
			e.finalizeRun();
			Controller controller = e.getController();
			if (controller == null) {
				continue;
			}

			controller.finalizeTick();

			if (e instanceof Player) {
				Player p = (Player) e;
				if (p.isOnline()) {
					p.getNetworkSynchronizer().finalizeTick();
				}
			}
		}
	}

	public void preSnapshotRun() {
		for (SpoutEntity e : entities.get().values()) {
			if (e.getController() != null) {
				if (e instanceof Player) {
					Player p = (Player) e;
					if (p.isOnline()) {
						p.getNetworkSynchronizer().preSnapshot();
					}
				}
			}
		}
	}

	/**
	 * Updates the snapshot for all entities
	 */
	public void copyAllSnapshots() {
		for (SpoutEntity e : entities.get().values()) {
			e.copySnapshot();
		}
		snapshotManager.copyAllSnapshots();
	}

	/**
	 * Gets the string map associated with this entity manager
	 * @return
	 */
	public StringMap getStringMap() {
		return entityMap;
	}

	/**
	 * The region this entity manager oversees, or null if it does not manage a region's entities
	 *
	 * @return region or null
	 */
	public SpoutRegion getRegion() {
		return null;
	}

	public Map<Vector3, Entity> getBlockEntities() {
		return Collections.unmodifiableMap(blockEntities);
	}

	public void syncEntities() {
		Map<Entity, Integer> observerSnapshot = observers.get();
		Map<Entity, Integer> observerLive = observers.getLive();

		List<Entity> entitiesSnapshot = entities.get();

		// Changed means entered/left the chunk
		List<Entity> changedEntities = entities.getDirtyList();
		List<Entity> changedObservers = observers.getDirtyList();

		if (entitiesSnapshot.size() > 0) {
			for (Entity p : changedObservers) {
				Integer playerDistanceOld = observerSnapshot.get(p);
				if (playerDistanceOld == null) {
					playerDistanceOld = Integer.MAX_VALUE;
				}
				Integer playerDistanceNew = observerLive.get(p);
				if (playerDistanceNew == null) {
					playerDistanceNew = Integer.MAX_VALUE;
				}
				// Player Network sync
				if (p instanceof Player) {
					Player player = (Player) p;

					NetworkSynchronizer n = player.getNetworkSynchronizer();
					for (Entity e : entitiesSnapshot) {
						if (player.equals(e)) {
							continue;
						}
						int entityViewDistanceOld = ((SpoutEntity) e).getPrevViewDistance();
						int entityViewDistanceNew = e.getViewDistance();

						if (playerDistanceOld <= entityViewDistanceOld && playerDistanceNew > entityViewDistanceNew) {
							n.destroyEntity(e);
						} else if (playerDistanceNew <= entityViewDistanceNew && playerDistanceOld > entityViewDistanceOld) {
							n.spawnEntity(e);
						}
					}
				}
			}
		}

		for (Entity e : changedEntities) {
			SpoutChunk oldChunk = (SpoutChunk) e.getChunk();
			if (((SpoutEntity) e).justSpawned()) {
				oldChunk = null;
			}
			SpoutChunk newChunk = (SpoutChunk) ((SpoutEntity) e).getChunkLive();
			if (!(oldChunk != null && oldChunk.equals(this)) && !((SpoutEntity) e).justSpawned()) {
				continue;
			}
			for (Entity p : observerLive.keySet()) {
				if (p == null || p.equals(e)) {
					continue;
				}
				if (p.getController() instanceof PlayerController) {
					Integer playerDistanceOld;
					if (oldChunk == null) {
						playerDistanceOld = Integer.MAX_VALUE;
					} else {
						playerDistanceOld = oldChunk.observers.getLive().get(p);
						if (playerDistanceOld == null) {
							playerDistanceOld = Integer.MAX_VALUE;
						}
					}
					Integer playerDistanceNew;
					if (newChunk == null) {
						playerDistanceNew = Integer.MAX_VALUE;
					} else {
						playerDistanceNew = newChunk.observers.getLive().get(p);
						if (playerDistanceNew == null) {
							playerDistanceNew = Integer.MAX_VALUE;
						}
					}
					int entityViewDistanceOld = ((SpoutEntity) e).getPrevViewDistance();
					int entityViewDistanceNew = e.getViewDistance();

					Player player = (Player) p.getController().getParent();

					if (!player.isOnline()) {
						continue;
					}
					NetworkSynchronizer n = player.getNetworkSynchronizer();
					if (playerDistanceOld <= entityViewDistanceOld && playerDistanceNew > entityViewDistanceNew) {
						n.destroyEntity(e);
					} else if (playerDistanceNew <= entityViewDistanceNew && playerDistanceOld > entityViewDistanceOld) {
						n.spawnEntity(e);
					}
				}
			}
		}

		// Update all entities that are in the chunk
		// TODO - should have sorting based on view distance
		for (Map.Entry<Entity, Integer> entry : observerLive.entrySet()) {
			Entity p = entry.getKey();
			if (p.getController() instanceof PlayerController) {
				Player player = (Player) p.getController().getParent();
				if (player.isOnline()) {
					NetworkSynchronizer n = player.getNetworkSynchronizer();
					int playerDistance = entry.getValue();
					Entity playerEntity = p;
					for (Entity e : entitiesSnapshot) {
						if (playerEntity != e) {
							if (playerDistance <= e.getViewDistance()) {
								if (((SpoutEntity) e).getPrevController() != e.getController()) {
									n.destroyEntity(e);
									n.spawnEntity(e);
								}
								n.syncEntity(e);
							}
						}
					}
					for (Entity e : changedEntities) {
						if (entitiesSnapshot.contains(e)) {
							continue;
						} else if (((SpoutEntity) e).justSpawned()) {
							if (playerEntity != e) {
								if (playerDistance <= e.getViewDistance()) {
									if (((SpoutEntity) e).getPrevController() != e.getController()) {
										n.destroyEntity(e);
										n.spawnEntity(e);
									}
									n.syncEntity(e);
								}
							}
						}
					}
				}
			}
		}
	}
}
