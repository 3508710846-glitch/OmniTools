package dev.modmind.omnitools.skills;

import com.google.gson.JsonParseException;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Locale;

/** Native attribute targets supported by the first skill-tree release. */
public enum SkillAttribute {
    BLOCK_BREAK_SPEED(Attributes.BLOCK_BREAK_SPEED),
    ATTACK_DAMAGE(Attributes.ATTACK_DAMAGE),
    ARMOR(Attributes.ARMOR),
    LUCK(Attributes.LUCK),
    MOVEMENT_SPEED(Attributes.MOVEMENT_SPEED),
    MAX_HEALTH(Attributes.MAX_HEALTH);

    private final Holder<Attribute> holder;

    SkillAttribute(Holder<Attribute> holder) {
        this.holder = holder;
    }

    public Holder<Attribute> holder() {
        return holder;
    }

    public static SkillAttribute parse(String value) {
        if (value == null || value.isBlank()) {
            throw new JsonParseException("skill attribute must be a non-empty string");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Unsupported skill attribute: " + value);
        }
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
