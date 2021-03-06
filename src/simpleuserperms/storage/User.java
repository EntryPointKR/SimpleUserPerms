package simpleuserperms.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import simpleuserperms.SimpleUserPerms;

public class User {

	protected final UUID uuid;

	protected Group group = SimpleUserPerms.getGroupsStorage().getDefaultGroup();
	protected final HashSet<Group> subGroups = new HashSet<>();
	protected final HashSet<String> additionalPerms = new HashSet<>();
	protected String prefix;
	protected String suffix;

	protected final HashMap<String, Boolean> activePermissions = new HashMap<>();

	protected final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	protected final ReadLock readLock = lock.readLock();
	protected final WriteLock writeLock = lock.writeLock();

	public User(UUID uuid) {
		this(uuid, true);
	}

	protected User(UUID uuid, boolean applyDefault) {
		this.uuid = uuid;
		if (applyDefault) {
			DefaultUserPermsCache.applyDefaultPermsTo(this);
		}
	}

	public boolean isDefault() {
		return
			group == SimpleUserPerms.getGroupsStorage().getDefaultGroup() &&
			subGroups.isEmpty() &&
			additionalPerms.isEmpty() &&
			prefix == null &&
			suffix == null;
	}

	public UUID getUUID() {
		return uuid;
	}

	public void setMainGroup(Group group) {
		setMainGroup(group, true);
	}

	public void setMainGroup(Group group, boolean recalculateNow) {
		writeLock.lock();
		try {
			this.group = group;
			if (recalculateNow) {
				recalculatePermissions();
			}
		} finally {
			writeLock.unlock();
		}
	}

	public Group getMainGroup() {
		readLock.lock();
		try {
			return group;
		} finally {
			readLock.unlock();
		}
	}

	public void addSubGroup(Group group) {
		addSubGroup(group, true);
	}

	public void addSubGroup(Group group, boolean recalculateNow) {
		writeLock.lock();
		try {
			subGroups.add(group);
			if (recalculateNow) {
				recalculatePermissions();
			}
		} finally {
			writeLock.unlock();
		}
	}

	public void removeSubGroup(Group group) {
		removeSubGroup(group, true);
	}

	public void removeSubGroup(Group group, boolean recalculateNow) {
		writeLock.lock();
		try {
			subGroups.remove(group);
			if (recalculateNow) {
				recalculatePermissions();
			}
		} finally {
			writeLock.unlock();
		}
	}

	public boolean hasSubGroup(Group group) {
		readLock.lock();
		try {
			return subGroups.contains(group);
		} finally {
			readLock.unlock();
		}
	}

	public List<Group> getSubGroups() {
		readLock.lock();
		try {
			return new ArrayList<Group>(subGroups);
		} finally {
			readLock.unlock();
		}
	}

	public void addAdditionalPermission(String permission) {
		addAdditionalPermission(permission, true);
	}

	public void addAdditionalPermission(String permission, boolean recalculateNow) {
		writeLock.lock();
		try {
			additionalPerms.add(permission);
			if (recalculateNow) {
				calculatePermission(permission);
				update();
			}
		} finally {
			writeLock.unlock();
		}
	}

	public void removeAdditionalPermission(String permission) {
		removeAdditionalPermission(permission, false);
	}

	public void removeAdditionalPermission(String permission, boolean recalculateNow) {
		writeLock.lock();
		try {
			additionalPerms.remove(permission);
			if (recalculateNow) {
				calculatePermission(permission);
				update();
			}
		} finally {
			writeLock.unlock();
		}
	}

	public boolean hasAdditionalPermission(String permission) {
		readLock.lock();
		try {
			return additionalPerms.contains(permission);
		} finally {
			readLock.unlock();
		}
	}

	public List<String> getAdditionalPermissions() {
		readLock.lock();
		try {
			return new ArrayList<String>(additionalPerms);
		} finally {
			readLock.unlock();
		}
	}

	public String getPrefix() {
		if (prefix != null) {
			return prefix;
		} else {
			return group.getPrefix();
		}
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	public String getSuffix() {
		if (suffix != null) {
			return suffix;
		} else {
			return group.getSuffix();
		}
	}

	public void setSuffix(String suffix) {
		this.suffix = suffix;
	}

	public void recalculatePermissions() {
		writeLock.lock();
		try {
			activePermissions.clear();
			subGroups.forEach(this::calculateGroupPerms);
			calculateGroupPerms(group);
			additionalPerms.forEach(this::calculatePermission);
		} finally {
			writeLock.unlock();
		}
		update();
	}

	protected void calculateGroupPerms(Group group) {
		group.parentGroups.forEach(this::calculateGroupPerms);
		group.permissions.forEach(this::calculatePermission);
	}

	protected void calculatePermission(String permission) {
		if (permission.startsWith("-")) {
			activePermissions.put(permission.substring(1, permission.length()), false);
		} else {
			activePermissions.put(permission, true);
		}
	}

	protected String lastName;

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	protected void update() {
		Runnable runnable = () -> {
			Player player = Bukkit.getPlayer(uuid);
			if (player != null) {
				SimpleUserPerms.getBukkitPermissions().updatePermissions(player);
			}
		};
		if (Bukkit.isPrimaryThread()) {
			runnable.run();
		} else {
			Bukkit.getScheduler().scheduleSyncDelayedTask(JavaPlugin.getPlugin(SimpleUserPerms.class), runnable);
		}
	}

	public Map<String, Boolean> getEffectivePermissions() {
		readLock.lock();
		try {
			return new HashMap<String, Boolean>(activePermissions);
		} finally {
			readLock.unlock();
		}
	}

	public ReadLockedEffectivePermissions getDirectEffectivePermissions() {
		return new ReadLockedEffectivePermissions(activePermissions, readLock);
	}

	public static class ReadLockedEffectivePermissions implements AutoCloseable {

		private Map<String, Boolean> activePerms;
		private ReadLock readLock;

		protected ReadLockedEffectivePermissions(Map<String, Boolean> activePerms, ReadLock readLock) {
			this.activePerms = Collections.unmodifiableMap(activePerms);
			this.readLock = readLock;
			this.readLock.lock();
		}

		public Map<String, Boolean> getEffectivePermissions() {
			return activePerms;
		}

		public void unlock() {
			activePerms = null;
			readLock.unlock();
		}

		@Override
		public void close() {
			unlock();
		}

	}

}
