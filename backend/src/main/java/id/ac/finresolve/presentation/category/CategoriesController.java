package id.ac.finresolve.presentation.category;

import id.ac.finresolve.api.CategoriesApi;
import id.ac.finresolve.api.model.Categories;
import id.ac.finresolve.presentation.mapper.ApiMapper;
import id.ac.finresolve.service.category.CategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the imported CFPB sample category catalog. */
@RestController
@RequestMapping("/api")
public class CategoriesController implements CategoriesApi {

    private final CategoryService categories;
    private final ApiMapper mapper;

    public CategoriesController(CategoryService categories, ApiMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Categories> getCategories() {
        return ResponseEntity.ok(mapper.categories(categories.list()));
    }
}
