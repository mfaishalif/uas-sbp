package id.ac.finresolve.categories;

import id.ac.finresolve.api.CategoriesApi;
import id.ac.finresolve.api.model.Categories;
import id.ac.finresolve.http.ApiMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the imported CFPB sample category catalog. */
@RestController
@RequestMapping("/api")
public class CategoriesController implements CategoriesApi {

    private final CategoryRepository repository;
    private final ApiMapper mapper;

    public CategoriesController(CategoryRepository repository, ApiMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Categories> getCategories() {
        return ResponseEntity.ok(mapper.categories(repository.list()));
    }
}
