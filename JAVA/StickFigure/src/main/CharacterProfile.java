package main;

// -----------------------------------------------------------------------------
// Everything Main needs to know about one playable figure: its asset files
// and the few tips its sequences refer to by name. Main itself stays
// figure-agnostic -- the selector (or 'h') switches between them.
final class CharacterProfile {
    final String name;
    final String initialPath; // skeleton + the pose Reset builds
    final String standingPathL; // what a walk stop / jump recovery morphs into, facing left...
    final String standingPathR; // ...and facing right
    // false: the figure faces the viewer (the human) -- its standing pose
    // works facing either way, so the one matching which foot is in front is
    // picked (mirrored) so ending a walk doesn't swing a leg through.
    // true: seen side-on (the horse) -- it has to face the way it goes, so it
    // turns around (flips) before walking or jumping the other way, and
    // stands facing the way it went.
    final boolean sideView;
    final String chargingPathL;
    final String chargingPathR;
    final String flyingPathL;
    final String flyingPathR;
    final String walkClipPathL; // walk-right is its mirror image
    final String skinPath; // null: plain ellipses
    final String cameraTip; // the camera follows this tip
    final String fixpointTip; // the pivot jump/recovery/manual morphs keep in place...
    final String takeoffTip; // ...except the jump's take-off (charging -> flying): what pushes off
    final String[] feetTips; // what it stands on

    private CharacterProfile(String name, String initialPath, String standingPathL, String standingPathR,
            boolean sideView, String chargingPathL, String chargingPathR,
            String flyingPathL, String flyingPathR, String walkClipPathL, String skinPath,
            String cameraTip, String fixpointTip, String takeoffTip, String... feetTips) {
        this.name = name;
        this.initialPath = initialPath;
        this.standingPathL = standingPathL;
        this.standingPathR = standingPathR;
        this.sideView = sideView;
        this.chargingPathL = chargingPathL;
        this.chargingPathR = chargingPathR;
        this.flyingPathL = flyingPathL;
        this.flyingPathR = flyingPathR;
        this.walkClipPathL = walkClipPathL;
        this.skinPath = skinPath;
        this.cameraTip = cameraTip;
        this.fixpointTip = fixpointTip;
        this.takeoffTip = takeoffTip;
        this.feetTips = feetTips;
    }

    // -------------------------------------------------------------------------
    String standingPathFor(double facingSign) {
        return facingSign < 0.0 ? standingPathL : standingPathR;
    }

    // -------------------------------------------------------------------------
    String chargingPathFor(double directionSign) {
        return directionSign < 0.0 ? chargingPathL : chargingPathR;
    }

    // -------------------------------------------------------------------------
    String flyingPathFor(double directionSign) {
        return directionSign < 0.0 ? flyingPathL : flyingPathR;
    }

    private static final String A = "src/assets/";

    static final CharacterProfile HUMAN = new CharacterProfile("Human",
            A + "Initial.json", A + "Initial.json", A + "Initial.json", false,
            A + "Pose_Charging_L.json", A + "Pose_Charging_R.json",
            A + "Pose_Flying_L.json", A + "Pose_Flying_R.json",
            A + "Anim_Walk_L.json", A + "Skin_initial.json",
            "head_neck", "shinL_foot", "shinL_foot", "shinL_foot", "shinR_foot");

    // pivots on a front hoof: gathering for a jump steps the hind legs in
    // under the body while the forelegs stay put. Takes off from a hind hoof,
    // like a real horse -- the forelegs tuck up, the hind legs stay down.
    static final CharacterProfile HORSE = new CharacterProfile("Horse",
            A + "Horse_Initial.json", A + "Horse_Pose_Standing_L.json", A + "Horse_Pose_Standing_R.json", true,
            A + "Horse_Pose_Charging_L.json", A + "Horse_Pose_Charging_R.json",
            A + "Horse_Pose_Flying_L.json", A + "Horse_Pose_Flying_R.json",
            A + "Horse_Anim_Walk_L.json", A + "Horse_Skin_initial.json",
            "spine_withers", "lowerForeLegL_hoof", "lowerHindLegL_hoof",
            "lowerHindLegL_hoof", "lowerHindLegR_hoof", "lowerForeLegL_hoof", "lowerForeLegR_hoof");

    // every playable figure, in selector order
    static final java.util.List<CharacterProfile> ALL = java.util.List.of(HUMAN, HORSE);

    // -------------------------------------------------------------------------
    static CharacterProfile byName(String name) {
        for (CharacterProfile p : ALL) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("no character " + name);
    }
}
