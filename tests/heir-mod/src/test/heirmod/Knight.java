package test.heirmod;

import test.heir.Guardian;

/** Extends a library class that extends a game class: its override must be baked through the library. */
public class Knight extends Guardian {
    public Knight(String name) {
        super(name);
    }

    @Override
    public int getScore() {
        return super.getScore() + 1;
    }
}
