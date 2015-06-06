/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esusers;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.support.RefreshListener;
import org.elasticsearch.shield.support.NoOpLogger;
import org.elasticsearch.shield.support.Validation;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.elasticsearch.shield.support.ShieldFiles.openAtomicMoveWriter;

/**
 *
 */
public class FileUserRolesStore {

    private static final Pattern USERS_DELIM = Pattern.compile("\\s*,\\s*");

    private final ESLogger logger;

    private final Path file;
    private CopyOnWriteArrayList<RefreshListener> listeners;
    private volatile ImmutableMap<String, String[]> userRoles;

    public FileUserRolesStore(RealmConfig config, ResourceWatcherService watcherService) {
        this(config, watcherService, null);
    }

    FileUserRolesStore(RealmConfig config, ResourceWatcherService watcherService, RefreshListener listener) {
        logger = config.logger(FileUserRolesStore.class);
        file = resolveFile(config.settings(), config.env());
        userRoles = parseFileLenient(file, logger);
        FileWatcher watcher = new FileWatcher(file.getParent());
        watcher.addListener(new FileListener());
        try {
            watcherService.add(watcher, ResourceWatcherService.Frequency.HIGH);
        } catch (IOException e) {
            throw new ElasticsearchException("failed to start watching the user roles file [" + file.toAbsolutePath() + "]", e);
        }

        listeners = new CopyOnWriteArrayList<>();
        if (listener != null) {
            listeners.add(listener);
        }
    }

    synchronized void addListener(RefreshListener listener) {
        listeners.add(listener);
    }

    int entriesCount() {
        return userRoles.size();
    }

    public String[] roles(String username) {
        if (userRoles == null) {
            return Strings.EMPTY_ARRAY;
        }
        String[] roles = userRoles.get(username);
        return roles == null ? Strings.EMPTY_ARRAY : userRoles.get(username);
    }

    public static Path resolveFile(Settings settings, Environment env) {
        String location = settings.get("files.users_roles");
        if (location == null) {
            return ShieldPlugin.resolveConfigFile(env, "users_roles");
        }
        return env.homeFile().resolve(location);
    }

    /**
     * Internally in this class, we try to load the file, but if for some reason we can't, we're being more lenient by
     * logging the error and skipping all enries. This is aligned with how we handle other auto-loaded files in shield.
     */
    static ImmutableMap<String, String[]> parseFileLenient(Path path, ESLogger logger) {
        try {
            return parseFile(path, logger);
        } catch (Throwable t) {
            logger.error("failed to parse users_roles file [{}]. skipping/removing all entries...", t, path.toAbsolutePath());
            return ImmutableMap.of();
        }
    }

    /**
     * parses the users_roles file. Should never return return {@code null}, if the file doesn't exist
     * an empty map is returned. The read file holds a mapping per line of the form "role -> users" while the returned
     * map holds entries of the form  "user -> roles".
     */
    public static ImmutableMap<String, String[]> parseFile(Path path, @Nullable ESLogger logger) {
        if (logger == null) {
            logger = NoOpLogger.INSTANCE;
        }
        logger.trace("reading users_roles file [{}]...", path.toAbsolutePath());


        if (!Files.exists(path)) {
            return ImmutableMap.of();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, Charsets.UTF_8);
        } catch (IOException ioe) {
            throw new ElasticsearchException("could not read users file [" + path.toAbsolutePath() + "]", ioe);
        }

        Map<String, List<String>> userToRoles = new HashMap<>();

        int lineNr = 0;
        for (String line : lines) {
            lineNr++;
            if (line.startsWith("#")) {  //comment
                continue;
            }
            int i = line.indexOf(":");
            if (i <= 0 || i == line.length() - 1) {
                logger.error("invalid entry in users_roles file [{}], line [{}]. skipping...", path.toAbsolutePath(), lineNr);
                continue;
            }
            String role = line.substring(0, i).trim();
            Validation.Error validationError = Validation.Roles.validateRoleName(role);
            if (validationError != null) {
                logger.error("invalid role entry in users_roles file [{}], line [{}] - {}. skipping...",  path.toAbsolutePath(), lineNr, validationError);
                continue;
            }
            String usersStr = line.substring(i + 1).trim();
            if (Strings.isEmpty(usersStr)) {
                logger.error("invalid entry for role [{}] in users_roles file [{}], line [{}]. no users found. skipping...", role, path.toAbsolutePath(), lineNr);
                continue;
            }
            String[] roleUsers = USERS_DELIM.split(usersStr);
            if (roleUsers.length == 0) {
                logger.error("invalid entry for role [{}] in users_roles file [{}], line [{}]. no users found. skipping...", role, path.toAbsolutePath(), lineNr);
                continue;
            }

            for (String user : roleUsers) {
                List<String> roles = userToRoles.get(user);
                if (roles == null) {
                    roles = new ArrayList<>();
                    userToRoles.put(user, roles);
                }
                roles.add(role);
            }
        }

        ImmutableMap.Builder<String, String[]> builder = ImmutableMap.builder();
        for (Map.Entry<String, List<String>> entry : userToRoles.entrySet()) {
            builder.put(entry.getKey(), entry.getValue().toArray(new String[entry.getValue().size()]));
        }

        ImmutableMap<String, String[]> usersRoles = builder.build();
        if (usersRoles.isEmpty()){
            logger.warn("no entries found in users_roles file [{}]. use bin/shield/esusers to add users and role mappings", path.toAbsolutePath());
        }

        return usersRoles;
    }

    /**
     * Accepts a mapping of user -> list of roles
     */
    public static void writeFile(Map<String, String[]> userToRoles, Path path) {
        HashMap<String, List<String>> roleToUsers = new HashMap<>();
        for (Map.Entry<String, String[]> entry : userToRoles.entrySet()) {
            for (String role : entry.getValue()) {
                List<String> users = roleToUsers.get(role);
                if (users == null) {
                    users = new ArrayList<>();
                    roleToUsers.put(role, users);
                }
                users.add(entry.getKey());
            }
        }

        try (PrintWriter writer = new PrintWriter(openAtomicMoveWriter(path))) {
            for (Map.Entry<String, List<String>> entry : roleToUsers.entrySet()) {
                writer.printf(Locale.ROOT, "%s:%s%s", entry.getKey(), Strings.collectionToCommaDelimitedString(entry.getValue()), System.lineSeparator());
            }
        } catch (IOException ioe) {
            throw new ElasticsearchException("could not write file [" + path.toAbsolutePath() + "], please check file permissions", ioe);
        }
    }

    public void notifyRefresh() {
        for (RefreshListener listener : listeners) {
            listener.onRefresh();
        }
    }

    private class FileListener extends FileChangesListener {
        @Override
        public void onFileCreated(Path file) {
            onFileChanged(file);
        }

        @Override
        public void onFileDeleted(Path file) {
            onFileChanged(file);
        }

        @Override
        public void onFileChanged(Path file) {
            if (file.equals(FileUserRolesStore.this.file)) {
                logger.info("users_roles file [{}] changed. updating users roles...", file.toAbsolutePath());
                userRoles = parseFileLenient(file, logger);
                notifyRefresh();
            }
        }
    }
}
