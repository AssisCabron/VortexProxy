package org.assiscabron.vortexProxy.core.backend;

import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.instance.generator.GenerationUnit;

/**
 * Procedural world generator using layered Perlin noise for natural-looking terrain.
 * Generates smooth hills, valleys, and water features without external dependencies.
 */
public final class VortexWorldGenerator implements Generator {
    private static final int SEA_LEVEL = 62;
    private static final int BASE_HEIGHT = 64;

    private final int[] perm = new int[512];
    private final int seed;

    public VortexWorldGenerator() {
        this(new java.util.Random().nextInt());
    }

    public VortexWorldGenerator(int seed) {
        this.seed = seed;
        int[] base = new int[256];
        for (int i = 0; i < 256; i++) base[i] = i;
        var rng = new java.util.Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = base[i];
            base[i] = base[j];
            base[j] = tmp;
        }
        for (int i = 0; i < 512; i++) perm[i] = base[i & 255];
    }

    @Override
    public void generate(GenerationUnit unit) {
        var start = unit.absoluteStart();
        var size = unit.size();
        var modifier = unit.modifier();

        for (int x = 0; x < size.blockX(); x++) {
            for (int z = 0; z < size.blockZ(); z++) {
                int absX = start.blockX() + x;
                int absZ = start.blockZ() + z;

                // Fractal Perlin noise (4 octaves)
                double elevation = 0;
                double amp = 1.0;
                double freq = 0.005;
                double totalAmp = 0;

                for (int oct = 0; oct < 4; oct++) {
                    elevation += perlin(absX * freq, absZ * freq) * amp;
                    totalAmp += amp;
                    amp *= 0.5;
                    freq *= 2.0;
                }
                elevation /= totalAmp;

                // Epic Mountains Math: Apply exponential curve to make valleys flat but peaks shoot up massively
                double steepness = Math.pow(Math.abs(elevation), 1.6) * Math.signum(elevation);
                int height = (int) (BASE_HEIGHT + steepness * 65); 
                if (height < 5) height = 5; // Enforce minimum floor limit

                // --- Fill each block in the column individually ---
                modifier.setBlock(absX, 0, absZ, Block.BEDROCK);

                for (int y = 1; y < height - 3; y++) {
                    modifier.setBlock(absX, y, absZ, Block.STONE);
                }

                for (int y = Math.max(1, height - 3); y < height; y++) {
                    modifier.setBlock(absX, y, absZ, Block.DIRT);
                }

                boolean isGrass = false;
                if (height <= SEA_LEVEL) {
                    modifier.setBlock(absX, height, absZ, Block.SAND);
                } else {
                    modifier.setBlock(absX, height, absZ, Block.GRASS_BLOCK);
                    isGrass = true;
                }

                for (int y = height + 1; y <= SEA_LEVEL; y++) {
                    modifier.setBlock(absX, y, absZ, Block.WATER);
                }

                // --- Stylized High Trees Generation ---
                if (isGrass && height > SEA_LEVEL + 1) {
                    // Fast pseudo-random determination using local coordinates to scatter trees deterministically
                    int hash = (absX * 73856093 ^ absZ * 19349663 ^ seed) & 511;
                    if (hash < 6) { // Probability of a tree spawning
                        generateStylizedTree(unit, absX, height + 1, absZ, hash);
                    }
                }
            }
        }
    }

    private void generateStylizedTree(GenerationUnit unit, int rx, int ry, int rz, int randomness) {
        var modifier = unit.modifier();
        int minX = unit.absoluteStart().blockX();
        int maxX = unit.absoluteEnd().blockX();
        int minZ = unit.absoluteStart().blockZ();
        int maxZ = unit.absoluteEnd().blockZ();

        // Aesthetic Tree: Tall trunk, layered canopy
        int height = 5 + (randomness % 4); 
        
        // Trunk
        for (int y = 0; y < height; y++) {
             if (rx >= minX && rx < maxX && rz >= minZ && rz < maxZ) {
                 modifier.setBlock(rx, ry + y, rz, Block.OAK_LOG);
             }
        }

        // Leaves (a tiered pine-like structure, very clean and mod-like)
        int leafStart = ry + height - 3;
        for (int y = leafStart; y <= ry + height + 1; y++) {
             int radius = y >= ry + height ? 1 : 2;
             for (int dx = -radius; dx <= radius; dx++) {
                 for (int dz = -radius; dz <= radius; dz++) {
                     // don't always replace if corner etc to make it somewhat diamond/rounded shaped
                     if (Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 1) continue; 
                     
                     // do not overwrite the trunk
                     if (dx == 0 && dz == 0 && y < ry + height) continue;

                     int targetX = rx + dx;
                     int targetZ = rz + dz;
                     if (targetX >= minX && targetX < maxX && targetZ >= minZ && targetZ < maxZ) {
                         modifier.setBlock(targetX, y, targetZ, Block.OAK_LEAVES);
                     }
                 }
             }
        }
    }

    private double perlin(double x, double z) {
        int xi = (int) Math.floor(x) & 255;
        int zi = (int) Math.floor(z) & 255;
        double xf = x - Math.floor(x);
        double zf = z - Math.floor(z);
        double u = fade(xf);
        double v = fade(zf);
        int aa = perm[perm[xi] + zi];
        int ab = perm[perm[xi] + zi + 1];
        int ba = perm[perm[xi + 1] + zi];
        int bb = perm[perm[xi + 1] + zi + 1];
        double x1 = lerp(u, grad(aa, xf, zf), grad(ba, xf - 1, zf));
        double x2 = lerp(u, grad(ab, xf, zf - 1), grad(bb, xf - 1, zf - 1));
        return lerp(v, x1, x2);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double z) {
        return switch (hash & 3) {
            case 0 ->  x + z;
            case 1 -> -x + z;
            case 2 ->  x - z;
            case 3 -> -x - z;
            default -> 0;
        };
    }
}
