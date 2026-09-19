package skeleton;

// -----------------------------------------------------------------------------
// A behavior layered on top of a Body's own physics, run once per substep by
// animation.World -- not part of the skeleton data model itself (unlike Bone/
// Joint/Motor), but external logic that's allowed to reach in and adjust things
// (e.g. a motor's target angle) based on the body's current state.
public abstract class PhysicalUpgrades {
    public abstract void process();
}
