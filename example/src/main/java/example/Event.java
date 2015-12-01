package example;

import adt.Adt;
import adt.AdtFields;
import adt.NameAndType;
import org.joda.money.Money;

@Adt(baseName = "Events", packageName = "com.example.event")
public enum Event {
    @AdtFields({
            @NameAndType(name = "id", type = Long.class),
            @NameAndType(name = "amount", type = Money.class)
    })
    Offer,
    @AdtFields()
    Accept,
    @AdtFields()
    Reject;
}
