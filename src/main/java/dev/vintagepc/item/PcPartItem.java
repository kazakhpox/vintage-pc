package dev.vintagepc.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class PcPartItem extends Item {
    public final PcPart part;

    public PcPartItem(PcPart part, Properties props) {
        super(props);
        this.part = part;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable(part == PcPart.MOTHERBOARD ? "vintagepc.part_tip_mb"
                : part.required ? "vintagepc.part_tip" : "vintagepc.part_tip_optional"));
    }
}
