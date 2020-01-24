/*
 * Roughly Enough Items by Danielshe.
 * Licensed under the MIT License.
 */

package me.shedaniel.rei.impl.compat;

import net.minecraft.client.render.model.BakedModel;

import java.util.function.Predicate;

public class ModelSideLit1152Compat implements Predicate<BakedModel> {
    @Override
    public boolean test(BakedModel bakedModel) {
        return bakedModel.isSideLit();
    }
}
