package dev.anima.renderer;

public enum BlockType {
    AIR(false, true, false, false, 0),
    GRASS(true, false, true, false, 0),
    DIRT(true, false, false, false, 0),
    STONE(true, false, false, false, 0),
    COBBLESTONE(true, false, false, false, 0),
    SAND(true, false, false, false, 0),
    GRAVEL(true, false, false, false, 0),
    WATER(false, true, false, false, 0),
    OAK_LOG(true, false, false, false, 0),
    OAK_LEAVES(true, true, true, false, 0),
    OAK_PLANKS(true, false, false, false, 0),
    TALL_GRASS(false, true, true, true, 0),
    POPPY(false, true, true, true, 0),
    DANDELION(false, true, true, true, 0),
    TORCH(false, true, false, true, 14);

    public final boolean solid;
    public final boolean transparent;
    public final boolean foliage;
    public final boolean crossModel;
    public final int emissiveLight;

    BlockType(boolean solid, boolean transparent, boolean foliage, boolean crossModel, int emissiveLight) {
        this.solid = solid;
        this.transparent = transparent;
        this.foliage = foliage;
        this.crossModel = crossModel;
        this.emissiveLight = emissiveLight;
    }

    public boolean occludesLight() {
        return solid && !transparent;
    }
}
