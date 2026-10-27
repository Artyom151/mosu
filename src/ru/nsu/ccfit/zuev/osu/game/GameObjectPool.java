package ru.nsu.ccfit.zuev.osu.game;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import ru.nsu.ccfit.zuev.osu.Config;
import ru.nsu.ccfit.zuev.osu.ResourceManager;

public class GameObjectPool {
    public static GameObjectPool instance = new GameObjectPool();
    public LinkedList<GameplayHitCircle> circles = new LinkedList<>();
    public Map<String, LinkedList<GameEffect>> effects = new HashMap<>();
    public LinkedList<GameplaySlider> sliders = new LinkedList<>();
    public LinkedList<GameplaySpinner> spinners = new LinkedList<>();
    private int objectsCreated = 0;
    private GameObjectPool() {
    }

    public static GameObjectPool getInstance() {
        return instance;
    }

    public GameplayHitCircle getCircle() {
        if (!circles.isEmpty()) {
            return circles.poll();
        }

        objectsCreated++;
        return new GameplayHitCircle();
    }

    public void putCircle(final GameplayHitCircle circle) {
        circles.add(circle);
    }

    public GameplaySpinner getSpinner() {
        boolean modern = useModernSpinner();

        for (var it = spinners.iterator(); it.hasNext(); ) {
            var spinner = it.next();

            if ((spinner instanceof GameplayModernSpinner) == modern) {
                it.remove();
                return spinner;
            }
        }

        objectsCreated++;

        return modern ? new GameplayModernSpinner() : new GameplaySpinner();
    }

    private static boolean useModernSpinner() {
        // Skins that only provide osu!stable spinner textures would be ignored by the modern spinner.
        return Config.getSpinnerStyle() == 1 && !ResourceManager.getInstance().isClassicSpinnerSkin();
    }

    public void putSpinner(final GameplaySpinner spinner) {
        spinners.add(spinner);
    }

    public GameEffect getEffect(final String texname) {
        if (effects.containsKey(texname) && !effects.get(texname).isEmpty()) {
            return effects.get(texname).poll();
        }

        objectsCreated++;
        return new GameEffect(texname);
    }

    public void putEffect(final GameEffect effect) {
        if (!effects.containsKey(effect.getTexname())) {
            effects.put(effect.getTexname(), new LinkedList<>());
        }

        effects.get(effect.getTexname()).add(effect);
    }

    public GameplaySlider getSlider() {
        if (!sliders.isEmpty()) {
            return sliders.poll();
        }

        objectsCreated++;
        return new GameplaySlider();
    }

    public void putSlider(final GameplaySlider slider) {
        sliders.add(slider);
    }

    public int getObjectsCreated() {
        return objectsCreated;
    }

    public void purge() {
        effects.clear();
        circles.clear();
        sliders.clear();
        spinners.clear();

        objectsCreated = 0;
    }

    public void preload() {
        for (int i = 0; i < 10; i++) {
            putCircle(new GameplayHitCircle());
        }
        for (int i = 0; i < 5; i++) {
            putSlider(new GameplaySlider());
        }
        for (int i = 0; i < 3; i++) {
            putSpinner(useModernSpinner() ? new GameplayModernSpinner() : new GameplaySpinner());
        }

        objectsCreated = circles.size() + sliders.size() + spinners.size();
    }
}
