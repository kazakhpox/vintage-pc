package dev.vintagepc.item;

/** Комплектующие ретро-компьютера. Материнская плата ставится первой, Wi-Fi-карта — по желанию. */
public enum PcPart {
    MOTHERBOARD("motherboard", true),
    CPU("cpu", true),
    RAM("ram", true),
    GPU("gpu", true),
    HDD("hdd", true),
    PSU("psu", true),
    WIFI("wifi_card", false);

    public final String id;
    public final boolean required;

    PcPart(String id, boolean required) { this.id = id; this.required = required; }

    public String langKey() { return "item.vintagepc." + id; }
}
