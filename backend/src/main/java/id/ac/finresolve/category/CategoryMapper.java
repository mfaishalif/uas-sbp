package id.ac.finresolve.category;

import id.ac.finresolve.api.model.Categories;
import id.ac.finresolve.api.model.CategoryTuple;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps the category catalog values to the generated transport model. */
@Component
public final class CategoryMapper {

    public Categories categories(List<CategoryValues> values) {
        List<CategoryTuple> items = new ArrayList<>(values.size());
        for (CategoryValues value : values) {
            items.add(new CategoryTuple(
                    value.product(), value.subProduct(), value.issue(), value.subIssue()));
        }
        return new Categories(items, Categories.ScopeEnum.IMPORTED_CFPB_SAMPLE);
    }
}
