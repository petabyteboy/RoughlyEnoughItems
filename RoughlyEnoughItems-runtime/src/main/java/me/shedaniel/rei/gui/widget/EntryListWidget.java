/*
 * This file is licensed under the MIT License, part of Roughly Enough Items.
 * Copyright (c) 2018, 2019, 2020 shedaniel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.shedaniel.rei.gui.widget;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.math.Matrix4f;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import me.shedaniel.clothconfig2.ClothConfigInitializer;
import me.shedaniel.clothconfig2.api.ScissorsHandler;
import me.shedaniel.clothconfig2.api.ScrollingContainer;
import me.shedaniel.clothconfig2.gui.widget.DynamicNewSmoothScrollingEntryListWidget;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.math.impl.PointHelper;
import me.shedaniel.rei.RoughlyEnoughItemsCore;
import me.shedaniel.rei.api.*;
import me.shedaniel.rei.api.widgets.Tooltip;
import me.shedaniel.rei.gui.ContainerScreenOverlay;
import me.shedaniel.rei.gui.config.EntryPanelOrdering;
import me.shedaniel.rei.impl.*;
import me.shedaniel.rei.utils.CollectionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableLong;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@ApiStatus.Internal
public class EntryListWidget extends WidgetWithBounds {
    
    static final Comparator<? super EntryStack> ENTRY_NAME_COMPARER = Comparator.comparing(stack -> stack.asFormatStrippedText().getString());
    static final Comparator<? super EntryStack> ENTRY_GROUP_COMPARER = Comparator.comparingInt(stack -> {
        if (stack.getType() == EntryStack.Type.ITEM) {
            CreativeModeTab group = stack.getItem().getItemCategory();
            if (group != null)
                return group.getId();
        }
        return Integer.MAX_VALUE;
    });
    private static final int SIZE = 18;
    private static final boolean LAZY = true;
    private static int page;
    protected final ScrollingContainer scrolling = new ScrollingContainer() {
        @Override
        public Rectangle getBounds() {
            return EntryListWidget.this.getBounds();
        }
        
        @Override
        public int getMaxScrollHeight() {
            return Mth.ceil((allStacks.size() + blockedCount) / (innerBounds.width / (float) entrySize())) * entrySize();
        }
    };
    protected int blockedCount;
    private boolean debugTime;
    private double lastAverageDebugTime;
    private double averageDebugTime;
    private double lastTotalDebugTime;
    private double totalDebugTime;
    private float totalDebugTimeDelta;
    private Rectangle bounds, innerBounds;
    private List<EntryStack> allStacks = null;
    private List<EntryListEntry> entries = Collections.emptyList();
    private List<Widget> renders = Collections.emptyList();
    private List<Widget> widgets = Collections.emptyList();
    private List<SearchArgument.SearchArguments> lastSearchArguments = Collections.emptyList();
    private String lastSearchTerm = null;
    
    public static int entrySize() {
        return Mth.ceil(SIZE * ConfigObject.getInstance().getEntrySize());
    }
    
    static boolean notSteppingOnExclusionZones(int left, int top, int width, int height, Rectangle listArea) {
        Minecraft instance = Minecraft.getInstance();
        for (OverlayDecider decider : DisplayHelper.getInstance().getSortedOverlayDeciders(instance.screen.getClass())) {
            InteractionResult fit = canItemSlotWidgetFit(left, top, width, height, decider);
            if (fit != InteractionResult.PASS)
                return fit == InteractionResult.SUCCESS;
        }
        return true;
    }
    
    private static InteractionResult canItemSlotWidgetFit(int left, int top, int width, int height, OverlayDecider decider) {
        InteractionResult fit;
        fit = decider.isInZone(left, top);
        if (fit != InteractionResult.PASS)
            return fit;
        fit = decider.isInZone(left + width, top);
        if (fit != InteractionResult.PASS)
            return fit;
        fit = decider.isInZone(left, top + height);
        if (fit != InteractionResult.PASS)
            return fit;
        fit = decider.isInZone(left + width, top + height);
        return fit;
    }
    
    private static Rectangle updateInnerBounds(Rectangle bounds) {
        bounds = bounds.clone();
        int widthReduction = (int) Math.round(bounds.width * (1 - ConfigObject.getInstance().getHorizontalEntriesBoundaries()));
        int heightReduction = (int) Math.round(bounds.width * (1 - ConfigObject.getInstance().getVerticalEntriesBoundaries()));
        bounds.x += widthReduction;
        bounds.width -= widthReduction;
        bounds.y += heightReduction / 2;
        bounds.height -= heightReduction;
        int entrySize = entrySize();
        if (ConfigObject.getInstance().isEntryListWidgetScrolled()) {
            int width = Math.max(Mth.floor((bounds.width - 2 - 6) / (float) entrySize), 1);
            if (ConfigObject.getInstance().isLeftHandSidePanel())
                return new Rectangle((int) (bounds.getCenterX() - width * (entrySize / 2f) + 3), bounds.y, width * entrySize, bounds.height);
            return new Rectangle((int) (bounds.getCenterX() - width * (entrySize / 2f) - 3), bounds.y, width * entrySize, bounds.height);
        }
        int width = Math.max(Mth.floor((bounds.width - 2) / (float) entrySize), 1);
        int height = Math.max(Mth.floor((bounds.height - 2) / (float) entrySize), 1);
        return new Rectangle((int) (bounds.getCenterX() - width * (entrySize / 2f)), (int) (bounds.getCenterY() - height * (entrySize / 2f)), width * entrySize, height * entrySize);
    }
    
    @Override
    public boolean mouseScrolled(double double_1, double double_2, double double_3) {
        if (bounds.contains(double_1, double_2)) {
            if (Screen.hasControlDown()) {
                ConfigObjectImpl config = ConfigManagerImpl.getInstance().getConfig();
                if (config.setEntrySize(config.getEntrySize() + double_3 * 0.075)) {
                    ConfigManager.getInstance().saveConfig();
                    REIHelper.getInstance().getOverlay().ifPresent(REIOverlay::queueReloadOverlay);
                    return true;
                }
            } else if (ConfigObject.getInstance().isEntryListWidgetScrolled()) {
                scrolling.offset(ClothConfigInitializer.getScrollStep() * -double_3 * (Screen.hasAltDown() ? 3 : 1), true);
                return true;
            }
        }
        return super.mouseScrolled(double_1, double_2, double_3);
    }
    
    @NotNull
    @Override
    public Rectangle getBounds() {
        return bounds;
    }
    
    public int getPage() {
        return page;
    }
    
    public void setPage(int page) {
        EntryListWidget.page = page;
    }
    
    public void previousPage() {
        page--;
    }
    
    public void nextPage() {
        page++;
    }
    
    public int getTotalPages() {
        if (ConfigObject.getInstance().isEntryListWidgetScrolled())
            return 1;
        return Mth.ceil(allStacks.size() / (float) entries.size());
    }
    
    public static <T extends EntryListEntryWidget> void renderEntries(MutableInt size, MutableLong time, boolean fastEntryRendering, PoseStack matrices, int mouseX, int mouseY, float delta, Iterable<T> entries) {
        renderEntries(true, size, time, fastEntryRendering, matrices, mouseX, mouseY, delta, entries);
    }
    
    public static <T extends EntryListEntryWidget> void renderEntries(boolean fastEntryRendering, PoseStack matrices, int mouseX, int mouseY, float delta, Iterable<T> entries) {
        renderEntries(false, null, null, fastEntryRendering, matrices, mouseX, mouseY, delta, entries);
    }
    
    public static <T extends EntryListEntryWidget> void renderEntries(boolean debugTime, MutableInt size, MutableLong time, boolean fastEntryRendering, PoseStack matrices, int mouseX, int mouseY, float delta, Iterable<T> entries) {
        T firstWidget = Iterables.getFirst(entries, null);
        if (firstWidget == null) return;
        EntryStack first = firstWidget.getCurrentEntry();
        if (fastEntryRendering && first instanceof OptimalEntryStack) {
            OptimalEntryStack firstStack = (OptimalEntryStack) first;
            firstStack.optimisedRenderStart(matrices, delta);
            long l = debugTime ? System.nanoTime() : 0;
            MultiBufferSource.BufferSource immediate = Minecraft.getInstance().renderBuffers().bufferSource();
            for (T listEntry : entries) {
                EntryStack currentEntry = listEntry.getCurrentEntry();
                currentEntry.setZ(100);
                listEntry.drawBackground(matrices, mouseX, mouseY, delta);
                ((OptimalEntryStack) currentEntry).optimisedRenderBase(matrices, immediate, listEntry.getInnerBounds(), mouseX, mouseY, delta);
                if (debugTime && !currentEntry.isEmpty()) size.increment();
            }
            immediate.endBatch();
            for (T listEntry : entries) {
                EntryStack currentEntry = listEntry.getCurrentEntry();
                ((OptimalEntryStack) currentEntry).optimisedRenderOverlay(matrices, listEntry.getInnerBounds(), mouseX, mouseY, delta);
                if (listEntry.containsMouse(mouseX, mouseY)) {
                    listEntry.queueTooltip(matrices, mouseX, mouseY, delta);
                    listEntry.drawHighlighted(matrices, mouseX, mouseY, delta);
                }
            }
            if (debugTime) time.add(System.nanoTime() - l);
            firstStack.optimisedRenderEnd(matrices, delta);
        } else {
            for (T entry : entries) {
                if (entry.getCurrentEntry().isEmpty())
                    continue;
                if (debugTime) {
                    size.increment();
                    long l = System.nanoTime();
                    entry.render(matrices, mouseX, mouseY, delta);
                    time.add(System.nanoTime() - l);
                } else entry.render(matrices, mouseX, mouseY, delta);
            }
        }
    }
    
    @Override
    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        MutableInt size = new MutableInt();
        MutableLong time = new MutableLong();
        long totalTimeStart = debugTime ? System.nanoTime() : 0;
        boolean fastEntryRendering = ConfigObject.getInstance().doesFastEntryRendering();
        if (ConfigObject.getInstance().isEntryListWidgetScrolled()) {
            ScissorsHandler.INSTANCE.scissor(bounds);
            
            int skip = Math.max(0, Mth.floor(scrolling.scrollAmount / (float) entrySize()));
            int nextIndex = skip * innerBounds.width / entrySize();
            int i = nextIndex;
            int cont = nextIndex;
            blockedCount = 0;
            
            Int2ObjectMap<List<EntryListEntry>> grouping = new Int2ObjectOpenHashMap<>();
            List<EntryListEntry> toRender = new ArrayList<>();
            Consumer<EntryListEntry> add;
            
            if (fastEntryRendering) {
                add = entry -> {
                    int hash = OptimalEntryStack.groupingHashFrom(entry.getCurrentEntry());
                    List<EntryListEntry> entries = grouping.get(hash);
                    
                    if (entries == null) {
                        grouping.put(hash, entries = new ArrayList<>());
                    }
                    
                    entries.add(entry);
                };
            } else {
                add = toRender::add;
            }
            
            for (; cont < entries.size(); cont++) {
                EntryListEntry entry = entries.get(cont);
                
                Rectangle entryBounds = entry.getBounds();
                
                entryBounds.y = (int) (entry.backupY - scrolling.scrollAmount);
                if (entryBounds.y > this.bounds.getMaxY()) break;
                if (allStacks.size() <= i) break;
                if (notSteppingOnExclusionZones(entryBounds.x, entryBounds.y, entryBounds.width, entryBounds.height, innerBounds)) {
                    EntryStack stack = allStacks.get(i++);
                    if (!stack.isEmpty()) {
                        entry.clearStacks();
                        entry.entry(stack);
                        add.accept(entry);
                    }
                } else {
                    blockedCount++;
                }
            }
            
            if (fastEntryRendering) {
                for (List<EntryListEntry> entries : grouping.values()) {
                    renderEntries(debugTime, size, time, fastEntryRendering, matrices, mouseX, mouseY, delta, entries);
                }
            } else {
                renderEntries(debugTime, size, time, fastEntryRendering, matrices, mouseX, mouseY, delta, toRender);
            }
            
            updatePosition(delta);
            ScissorsHandler.INSTANCE.removeLastScissor();
            scrolling.renderScrollBar(0, 1, REIHelper.getInstance().isDarkThemeEnabled() ? 0.8f : 1f);
        } else {
            for (Widget widget : renders) {
                widget.render(matrices, mouseX, mouseY, delta);
            }
            if (fastEntryRendering) {
                entries.stream().collect(Collectors.groupingBy(entryListEntry -> OptimalEntryStack.groupingHashFrom(entryListEntry.getCurrentEntry()))).forEach((integer, entries) -> {
                    renderEntries(debugTime, size, time, fastEntryRendering, matrices, mouseX, mouseY, delta, entries);
                });
            } else {
                renderEntries(debugTime, size, time, fastEntryRendering, matrices, mouseX, mouseY, delta, entries);
            }
        }
        
        if (debugTime) {
            long totalTime = System.nanoTime() - totalTimeStart;
            averageDebugTime += (time.getValue() / size.doubleValue()) * delta;
            totalDebugTime += totalTime / 1000000d * delta;
            totalDebugTimeDelta += delta;
            if (totalDebugTimeDelta >= 20) {
                lastAverageDebugTime = averageDebugTime / totalDebugTimeDelta;
                lastTotalDebugTime = totalDebugTime / totalDebugTimeDelta;
                averageDebugTime = 0;
                totalDebugTime = 0;
                totalDebugTimeDelta = 0;
            } else if (lastAverageDebugTime == 0) {
                lastAverageDebugTime = time.getValue() / size.doubleValue();
                totalDebugTime = totalTime / 1000000d;
            }
            int z = getZ();
            setZ(500);
            Component debugText = new TextComponent(String.format("%d entries, avg. %.0fns, ttl. %.2fms, %s fps", size.getValue(), lastAverageDebugTime, lastTotalDebugTime, minecraft.fpsString.split(" ")[0]));
            int stringWidth = font.width(debugText);
            fillGradient(matrices, Math.min(bounds.x, minecraft.screen.width - stringWidth - 2), bounds.y, bounds.x + stringWidth + 2, bounds.y + font.lineHeight + 2, -16777216, -16777216);
            MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            matrices.pushPose();
            matrices.translate(0.0D, 0.0D, getZ());
            Matrix4f matrix = matrices.last().pose();
            font.drawInBatch(debugText.getVisualOrderText(), Math.min(bounds.x + 2, minecraft.screen.width - stringWidth), bounds.y + 2, -1, false, matrix, immediate, false, 0, 15728880);
            immediate.endBatch();
            setZ(z);
            matrices.popPose();
        }
        
        if (containsMouse(mouseX, mouseY) && ClientHelper.getInstance().isCheating() && !minecraft.player.getInventory().getCarried().isEmpty() && RoughlyEnoughItemsCore.canDeleteItems()) {
            EntryStack stack = EntryStack.create(minecraft.player.getInventory().getCarried().copy());
            if (stack.getType() == EntryStack.Type.FLUID) {
                Item bucketItem = stack.getFluid().getBucket();
                if (bucketItem != null) {
                    stack = EntryStack.create(bucketItem);
                }
            }
            for (Widget child : children()) {
                if (child.containsMouse(mouseX, mouseY) && child instanceof EntryWidget) {
                    if (((EntryWidget) child).cancelDeleteItems(stack)) {
                        return;
                    }
                }
            }
            Tooltip.create(new TranslatableComponent("text.rei.delete_items")).queue();
        }
    }
    
    private int getScrollbarMinX() {
        if (ConfigObject.getInstance().isLeftHandSidePanel())
            return bounds.x + 1;
        return bounds.getMaxX() - 7;
    }
    
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (scrolling.mouseDragged(mouseX, mouseY, button, dx, dy, ConfigObject.getInstance().doesSnapToRows(), entrySize()))
            return true;
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }
    
    private void updatePosition(float delta) {
        if (ConfigObject.getInstance().doesSnapToRows() && scrolling.scrollTarget >= 0 && scrolling.scrollTarget <= scrolling.getMaxScroll()) {
            double nearestRow = Math.round(scrolling.scrollTarget / (double) entrySize()) * (double) entrySize();
            if (!DynamicNewSmoothScrollingEntryListWidget.Precision.almostEquals(scrolling.scrollTarget, nearestRow, DynamicNewSmoothScrollingEntryListWidget.Precision.FLOAT_EPSILON))
                scrolling.scrollTarget += (nearestRow - scrolling.scrollTarget) * Math.min(delta / 2.0, 1.0);
            else
                scrolling.scrollTarget = nearestRow;
        }
        scrolling.updatePosition(delta);
    }
    
    @Override
    public boolean keyPressed(int int_1, int int_2, int int_3) {
        if (containsMouse(PointHelper.ofMouse()))
            for (Widget widget : widgets)
                if (widget.keyPressed(int_1, int_2, int_3))
                    return true;
        return false;
    }
    
    public void updateArea(@NotNull String searchTerm) {
        this.bounds = ScreenHelper.getItemListArea(ScreenHelper.getLastOverlay().getBounds());
        FavoritesListWidget favoritesListWidget = ContainerScreenOverlay.getFavoritesListWidget();
        if (favoritesListWidget != null)
            favoritesListWidget.updateFavoritesBounds(searchTerm);
        if (searchTerm != null || allStacks == null || (ConfigObject.getInstance().isFavoritesEnabled() && favoritesListWidget == null))
            updateSearch(searchTerm, true);
        else
            updateEntriesPosition();
    }
    
    public void updateEntriesPosition() {
        int entrySize = entrySize();
        this.innerBounds = updateInnerBounds(bounds);
        if (!ConfigObject.getInstance().isEntryListWidgetScrolled()) {
            this.renders = Lists.newArrayList();
            page = Math.max(page, 0);
            List<EntryListEntry> entries = Lists.newArrayList();
            int width = innerBounds.width / entrySize;
            int height = innerBounds.height / entrySize;
            for (int currentY = 0; currentY < height; currentY++) {
                for (int currentX = 0; currentX < width; currentX++) {
                    int slotX = currentX * entrySize + innerBounds.x;
                    int slotY = currentY * entrySize + innerBounds.y;
                    if (notSteppingOnExclusionZones(slotX - 1, slotY - 1, entrySize, entrySize, innerBounds)) {
                        entries.add((EntryListEntry) new EntryListEntry(slotX, slotY, entrySize).noBackground());
                    }
                }
            }
            page = Math.max(Math.min(page, getTotalPages() - 1), 0);
            List<EntryStack> subList = allStacks.stream().skip(Math.max(0, page * entries.size())).limit(Math.max(0, entries.size() - Math.max(0, -page * entries.size()))).collect(Collectors.toList());
            for (int i = 0; i < subList.size(); i++) {
                EntryStack stack = subList.get(i);
                entries.get(i + Math.max(0, -page * entries.size())).clearStacks().entry(stack);
            }
            this.entries = entries;
            this.widgets = Lists.newArrayList(renders);
            this.widgets.addAll(entries);
        } else {
            page = 0;
            int width = innerBounds.width / entrySize;
            int pageHeight = innerBounds.height / entrySize;
            int slotsToPrepare = Math.max(allStacks.size() * 3, width * pageHeight * 3);
            int currentX = 0;
            int currentY = 0;
            List<EntryListEntry> entries = Lists.newArrayList();
            for (int i = 0; i < slotsToPrepare; i++) {
                int xPos = currentX * entrySize + innerBounds.x;
                int yPos = currentY * entrySize + innerBounds.y;
                entries.add((EntryListEntry) new EntryListEntry(xPos, yPos, entrySize).noBackground());
                currentX++;
                if (currentX >= width) {
                    currentX = 0;
                    currentY++;
                }
            }
            this.entries = entries;
            this.widgets = Lists.newArrayList(renders);
            this.widgets.addAll(entries);
        }
        FavoritesListWidget favoritesListWidget = ContainerScreenOverlay.getFavoritesListWidget();
        if (favoritesListWidget != null)
            favoritesListWidget.updateEntriesPosition(entry -> true);
    }
    
    @ApiStatus.Internal
    public List<EntryStack> getAllStacks() {
        return allStacks;
    }
    
    public void updateSearch(String searchTerm) {
        updateSearch(searchTerm, true);
    }
    
    public void updateSearch(String searchTerm, boolean ignoreLastSearch) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        if (ignoreLastSearch || this.lastSearchTerm == null || !this.lastSearchTerm.equals(searchTerm)) {
            this.lastSearchTerm = searchTerm;
            this.lastSearchArguments = SearchArgument.processSearchTerm(searchTerm);
            List<EntryStack> list = Lists.newArrayList();
            boolean checkCraftable = ConfigManager.getInstance().isCraftableOnlyEnabled() && !ScreenHelper.inventoryStacks.isEmpty();
            IntSet workingItems = checkCraftable ? new IntOpenHashSet() : null;
            if (checkCraftable)
                workingItems.addAll(CollectionUtils.map(RecipeHelper.getInstance().findCraftableEntriesByItems(ScreenHelper.inventoryStacks), EntryStack::hashIgnoreAmount));
            List<EntryStack> stacks = EntryRegistry.getInstance().getPreFilteredList();
            if (stacks instanceof CopyOnWriteArrayList && !stacks.isEmpty()) {
                if (ConfigObject.getInstance().shouldAsyncSearch()) {
                    List<CompletableFuture<List<EntryStack>>> completableFutures = Lists.newArrayList();
                    for (Iterable<EntryStack> partitionStacks : CollectionUtils.partition(stacks, ConfigObject.getInstance().getAsyncSearchPartitionSize())) {
                        completableFutures.add(CompletableFuture.supplyAsync(() -> {
                            List<EntryStack> filtered = Lists.newArrayList();
                            for (EntryStack stack : partitionStacks) {
                                if (canLastSearchTermsBeAppliedTo(stack)) {
                                    if (workingItems != null && !workingItems.contains(stack.hashIgnoreAmount()))
                                        continue;
                                    filtered.add(stack.rewrap().setting(EntryStack.Settings.RENDER_COUNTS, EntryStack.Settings.FALSE));
                                }
                            }
                            return filtered;
                        }));
                    }
                    try {
                        CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        e.printStackTrace();
                    }
                    for (CompletableFuture<List<EntryStack>> future : completableFutures) {
                        List<EntryStack> now = future.getNow(null);
                        if (now != null)
                            list.addAll(now);
                    }
                } else {
                    for (EntryStack stack : stacks) {
                        if (canLastSearchTermsBeAppliedTo(stack)) {
                            if (workingItems != null && !workingItems.contains(stack.hashIgnoreAmount()))
                                continue;
                            list.add(stack.rewrap().setting(EntryStack.Settings.RENDER_COUNTS, EntryStack.Settings.FALSE));
                        }
                    }
                }
            }
            EntryPanelOrdering ordering = ConfigObject.getInstance().getItemListOrdering();
            if (ordering == EntryPanelOrdering.NAME)
                list.sort(ENTRY_NAME_COMPARER);
            if (ordering == EntryPanelOrdering.GROUPS)
                list.sort(ENTRY_GROUP_COMPARER);
            if (!ConfigObject.getInstance().isItemListAscending())
                Collections.reverse(list);
            allStacks = list;
        }
        debugTime = ConfigObject.getInstance().doDebugRenderTimeRequired();
        FavoritesListWidget favoritesListWidget = ContainerScreenOverlay.getFavoritesListWidget();
        if (favoritesListWidget != null)
            favoritesListWidget.updateSearch(this, searchTerm);
        if (ConfigObject.getInstance().doDebugSearchTimeRequired())
            RoughlyEnoughItemsCore.LOGGER.info("Search Used: %s", stopwatch.stop().toString());
        updateEntriesPosition();
    }
    
    public boolean canLastSearchTermsBeAppliedTo(EntryStack stack) {
        return lastSearchArguments.isEmpty() || SearchArgument.canSearchTermsBeAppliedTo(stack, lastSearchArguments);
    }
    
    @Override
    public List<? extends Widget> children() {
        return widgets;
    }
    
    @Override
    public boolean mouseClicked(double double_1, double double_2, int int_1) {
        if (ConfigObject.getInstance().isEntryListWidgetScrolled()) {
            if (scrolling.updateDraggingState(double_1, double_2, int_1))
                return true;
        }
        for (Widget widget : children())
            if (widget.mouseClicked(double_1, double_2, int_1))
                return true;
        return false;
    }
    
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (containsMouse(mouseX, mouseY)) {
            LocalPlayer player = minecraft.player;
            cancelDelete:
            if (ClientHelper.getInstance().isCheating() && player != null && player.getInventory() != null && !player.getInventory().getCarried().isEmpty() && RoughlyEnoughItemsCore.canDeleteItems()) {
                EntryStack stack = EntryStack.create(minecraft.player.getInventory().getCarried().copy());
                if (stack.getType() == EntryStack.Type.FLUID) {
                    Item bucketItem = stack.getFluid().getBucket();
                    if (bucketItem != null) {
                        stack = EntryStack.create(bucketItem);
                    }
                }
                for (Widget child : children()) {
                    if (child.containsMouse(mouseX, mouseY) && child instanceof EntryWidget) {
                        if (((EntryWidget) child).cancelDeleteItems(stack)) {
                            break cancelDelete;
                        }
                    }
                }
                
                ClientHelper.getInstance().sendDeletePacket();
                return true;
            }
            for (Widget widget : children())
                if (widget.mouseReleased(mouseX, mouseY, button))
                    return true;
        }
        return false;
    }
    
    private class EntryListEntry extends EntryListEntryWidget {
        private EntryListEntry(int x, int y, int entrySize) {
            super(new Point(x, y), entrySize);
        }
        
        @Override
        public boolean containsMouse(double mouseX, double mouseY) {
            return super.containsMouse(mouseX, mouseY) && bounds.contains(mouseX, mouseY);
        }
    }
}
