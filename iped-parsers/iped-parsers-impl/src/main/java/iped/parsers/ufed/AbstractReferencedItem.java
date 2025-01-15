package iped.parsers.ufed;

import iped.data.IItemReader;

public abstract class AbstractReferencedItem {

    protected IItemReader item;

    public AbstractReferencedItem(IItemReader item) {
        this.item = item;
    }

    public IItemReader getItem() {
        return item;
    }

}