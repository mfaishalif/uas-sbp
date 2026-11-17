package id.ac.finresolve.category;

import id.ac.finresolve.api.CategoriesApi;
import id.ac.finresolve.api.model.Categories;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the imported CFPB sample category catalog. */
@RestController
@RequestMapping("/api")
public class CategoriesController implements CategoriesApi {

    private final CategoryService categories;
    private final CategoryMapper mapper;

    public CategoriesController(CategoryService categories, CategoryMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Categories> getCategories() {
        return ResponseEntity.ok(mapper.categories(categories.list()));
    }
}
