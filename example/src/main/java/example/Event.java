package example;

import adt.ADT;
import adt.Field;
import adt.Fields;
import org.joda.money.Money;

@ADT(baseName = "Events", packageName = "com.example.event")
public enum Event {
    @Fields({
            @Field(name = "id", type = Long.class),
            @Field(name = "amount", type = Money.class)
    })
    Offer,
    @Fields()
    Accept,
    @Fields()
    Reject;
}
