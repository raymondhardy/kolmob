package net.sourceforge.kolmafia;

public interface KoLConstants {
    String LINE_BREAK = "\n";

    enum MafiaState {
        ABORT,
        ERROR,
        CONTINUE
    }

    enum ConsumptionType {
        FOOD,
        BOOZE,
        SPLEEN,
        FAMILIAR_EQUIPMENT,
        OTHER
    }
}