/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011-2012, Spout LLC <http://www.spout.org/>
 * Spout is licensed under the Spout License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the Spout License Version 1.
 *
 * Spout is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the Spout License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://spout.in/licensev1> for the full license, including
 * the MIT license.
 */
package org.spout.engine.resources;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bulletphysics.collision.shapes.CollisionShape;

import org.spout.api.component.implementation.ModelComponent;
import org.spout.api.component.implementation.PhysicsComponent;
import org.spout.api.component.type.EntityComponent;
import org.spout.api.entity.Entity;
import org.spout.api.entity.EntityPrefab;
import org.spout.api.geo.discrete.Point;
import org.spout.api.geo.discrete.Transform;
import org.spout.api.math.Quaternion;
import org.spout.api.math.Vector3;
import org.spout.api.resource.Resource;

import org.spout.engine.entity.SpoutEntity;
import org.spout.engine.entity.component.SpoutPhysicsComponent;

public class ClientEntityPrefab extends Resource implements EntityPrefab {
	private String name;
	private List<Class<? extends EntityComponent>> components = new ArrayList<Class<? extends EntityComponent>>();
	private Map<String, Object> datas = new HashMap<String, Object>();
	private Map<String, Float> collisionDatas = new HashMap<String, Float>();

	public ClientEntityPrefab(String name, List<Class<? extends EntityComponent>> components, Map<String, Object> datas, Map<String, Float> collisionDatas) {
		this.name = name;
		this.components.addAll(components);
		this.datas = datas;
		this.collisionDatas = collisionDatas;
	}

	public String getName() {
		return name;
	}

	public List<Class<? extends EntityComponent>> getComponents() {
		return components;
	}

	public Map<String, Object> getDatas() {
		return datas;
	}

	public Entity createEntity(Point point) {
		return createEntity(new Transform(point, Quaternion.IDENTITY, Vector3.ONE));
	}

	public Entity createEntity(Transform transform) {
		SpoutEntity entity = new SpoutEntity(transform);
		for (Class<? extends EntityComponent> c : components) {
			entity.add(c);
		}

		if (datas.containsKey("Model")) {
			ModelComponent mc = entity.get(ModelComponent.class);
			if (mc == null) {
				mc = entity.add(ModelComponent.class);
			}

			mc.setModel((String) datas.get("Model"));
		}
		Class collisionShape = (Class) datas.get("Shape");
		if (collisionShape != null && !collisionDatas.isEmpty()) {
			SpoutPhysicsComponent physics = (SpoutPhysicsComponent) entity.get(PhysicsComponent.class);
			if (physics == null) {
				entity.add(PhysicsComponent.class);
			}
			ArrayList<Float> bounds = new ArrayList<Float>();
			HashMap<String, Float> dampingValues = new HashMap<String, Float>();

			//Loop through the collision data section.
			for (Map.Entry entry : collisionDatas.entrySet()) {
				String name = entry.getKey().toString();
				Float value = (Float) entry.getValue();
				if (name.contains("Bounds")) {
					bounds.add(value);
					continue;
				}
				if (physics.getCollisionShape() == null) {
					//At this point, bounds has populated for the shape. We cannot just add in all the physics characteristics as some need a collision shape.
					//Instead we need to add the shape now.
					CollisionShape shape = null;
					ArrayList<Float> args = new ArrayList<Float>();
					int paramCount = 0;
					//Loop through the shape class' constructors, attempt to construct one with the bounds specified
					Constructor[] constructors = collisionShape.getConstructors();
					for (Constructor constructor: constructors) {
						for (Class<?> parameter : constructor.getParameterTypes()) {
							if (!parameter.getClass().equals(Float.class)) {
								throw new IllegalStateException(collisionShape.getName() + " isn't a valid shape for the bounds provided. Only floats are allowed.");
							}
							paramCount++;
						}
						//We have a valid constructor, lets initialize it.
						try {
							//Add the bounds values
							for (int i = 0; i < paramCount; i++) {
								args.add(collisionDatas.get("Bounds" + i));
							}
							shape = (CollisionShape) constructor.newInstance(args);
							break;
						} catch (Exception e) {
							throw new IllegalStateException("Could not construct a new instance of the CollisionShape: " + collisionShape.getName() + " for EntityPrefab: " + getName());
						}
					}
					physics.setCollisionShape(shape);
				}
				if (name.equalsIgnoreCase("Mass")) {
					physics.setMass(value);
				} else if (name.equalsIgnoreCase("AngularDamping")) {
					dampingValues.put("AngularDamping", value);
				} else if (name.equalsIgnoreCase("LinearDamping")) {
					dampingValues.put("LinearDamping", value);
				} else if (name.equalsIgnoreCase("Restitution")) {
					physics.setRestitution(value);
				} else if (name.equalsIgnoreCase("Friction")) {
					physics.setFriction(value);
				}
			}
		}
		return entity;
	}
}
