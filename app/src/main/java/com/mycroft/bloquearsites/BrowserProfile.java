package com.mycroft.bloquearsites;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BrowserProfile {
    private final Set<String> packageNames;
    private final List<String> addressViewIds;

    public BrowserProfile(String[] packageNames, String... addressViewIds) {
        this.packageNames = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(packageNames)));
        this.addressViewIds = Collections.unmodifiableList(Arrays.asList(addressViewIds));
    }

    public boolean matchesPackage(String packageName) {
        return packageName != null && packageNames.contains(packageName);
    }

    public List<String> getAddressViewIds() {
        return addressViewIds;
    }
}
