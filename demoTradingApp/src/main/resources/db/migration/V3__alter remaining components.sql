alter table holdings
    modify quantity decimal(19, 4) not null;

alter table orders
    modify quantity decimal(19, 4) not null;

alter table trades
    modify quantity decimal(19, 4) not null;

