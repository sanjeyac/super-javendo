package dev.superjavendo.input;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a {@link ControllerProfile} into a {@link ControllerInput}. New device kinds (or the future
 * configurator) plug in with {@link #register}.
 */
public final class ControllerFactory {
    private final Map<ControllerProfile.Type, Function<ControllerProfile, ControllerInput>> creators =
            new EnumMap<>(ControllerProfile.Type.class);

    public ControllerFactory() {
        register(ControllerProfile.Type.KEYBOARD, KeyboardInput::new);
        register(ControllerProfile.Type.GAMEPAD, GamepadInput::new);
    }

    public ControllerFactory register(ControllerProfile.Type type, Function<ControllerProfile, ControllerInput> creator) {
        creators.put(type, creator);
        return this;
    }

    public ControllerInput create(ControllerProfile profile) {
        Function<ControllerProfile, ControllerInput> c = creators.get(profile.type);
        if (c == null) throw new IllegalArgumentException("No implementation for " + profile.type);
        return c.apply(profile);
    }

    public ControllerInput createAll(ControllerProfile... profiles) {
        if (profiles.length == 0) return new NullInput();
        java.util.List<ControllerInput> list = new java.util.ArrayList<>();
        for (ControllerProfile p : profiles) list.add(create(p));
        return list.size() == 1 ? list.get(0) : new CompositeInput(list);
    }
}
