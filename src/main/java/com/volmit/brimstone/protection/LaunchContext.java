package com.volmit.brimstone.protection;

import java.util.List;
import java.util.UUID;

public record LaunchContext(UUID owner, String name, List<String> groups, UUID worldId,
                            boolean worldBypass, boolean regionBypass) {
    public LaunchContext {
        groups = List.copyOf(groups);
    }
}
