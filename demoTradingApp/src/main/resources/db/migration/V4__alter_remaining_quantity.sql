alter table orders
    modify remaining_quantity decimal(19, 4) not null;

alter table trades
    modify quantity decimal(19, 4) not null;

